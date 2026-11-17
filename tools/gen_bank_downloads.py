# -*- coding: utf-8 -*-
"""从内置 xsdanser_bank.db 生成官网题库下载包。

下载码即文件名：s<年级><a=上册|b=下册>，例如 s3a = 人教版 3 年级上册。
App 会请求 https://xsd.qx47.com/app/download/<下载码>.json。

题目字段与 QuizDb.importDownload() 完全对齐；与内置题库 qhash 相同的题
在 App 里会被自动跳过（页面已说明「已内置，下载码用于其他设备同步 / 恢复」）。
"""
import io
import json
import os
import sqlite3

BASE = os.path.dirname(os.path.abspath(__file__))
DB = os.path.join(BASE, "base_dec_v26", "assets", "xsdanser_bank.db")
OUT = os.path.join(os.path.dirname(BASE), "xsd-website", "app", "download")

COLS = ("edition", "grade", "grade_label", "term", "subject", "subject_group",
        "chapter", "topic", "topic_group", "text",
        "opt_a", "opt_b", "opt_c", "opt_d", "answer_index", "explain")


def valid(row):
    opts = [row["opt_" + k] or "" for k in "abcd"]
    opts = [o.strip() for o in opts]
    if any(not o for o in opts) or len(set(opts)) != 4:
        return False
    ai = row["answer_index"]
    if ai is None or not (0 <= int(ai) <= 3):
        return False
    text = (row["text"] or "").strip()
    return len(text) >= 2


def build(row):
    return {
        "edition": row["edition"] or "人教版",
        "grade": int(row["grade"]),
        "gradeLabel": row["grade_label"] or (str(row["grade"]) + "年级"),
        "term": row["term"] or "全学年",
        "subject": row["subject"] or "数学",
        "subjectGroup": row["subject_group"] or "",
        "chapter": row["chapter"] or "",
        "topic": row["topic"] or "",
        "topicGroup": row["topic_group"] or "",
        "text": (row["text"] or "").strip(),
        "options": [(row["opt_" + k] or "").strip() for k in "abcd"],
        "answerIndex": int(row["answer_index"]),
        "explain": row["explain"] or "",
    }


def main():
    if not os.path.isdir(OUT):
        os.makedirs(OUT)
    con = sqlite3.connect("file:" + DB.replace("\\", "/") + "?mode=ro", uri=True)
    con.row_factory = sqlite3.Row
    total = 0
    manifest = []
    for grade in range(1, 10):
        for suffix, term in (("a", "上册"), ("b", "下册")):
            rows = list(con.execute(
                "SELECT " + ",".join(COLS) + " FROM questions "
                "WHERE grade=? AND term=? ORDER BY subject, id", (grade, term)))
            qs = [build(r) for r in rows if valid(r)]
            code = "s%d%s" % (grade, suffix)
            doc = {
                "version": "xsd-bank-%s-1.0" % code,
                "name": "人教版 %d 年级%s · 简单" % (grade, term),
                "edition": "人教版",
                "grade": grade,
                "gradeLabel": "%d年级" % grade,
                "term": term,
                "level": "简单",
                "subjects": ["数学", "语文", "英语"],
                "questionCount": len(qs),
                "questions": qs,
            }
            path = os.path.join(OUT, code + ".json")
            with io.open(path, "w", encoding="utf-8", newline="\n") as f:
                json.dump(doc, f, ensure_ascii=False, separators=(",", ":"))
            size = os.path.getsize(path)
            total += len(qs)
            manifest.append((code, grade, term, len(qs), size))
            print("%-5s %d年级%-3s %3d 题 %7d B" % (code, grade, term, len(qs), size))
    con.close()
    print("[DONE] %d 个下载包 / %d 题 -> %s" % (len(manifest), total, OUT))
    assert len(manifest) == 18 and total == 10800, "题量或包数异常"


if __name__ == "__main__":
    main()
