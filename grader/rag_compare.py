"""15번: 제공 자료 통째로(full) vs RAG 채점 비교. 실험 폴더 두 개를 받아 자료 글자 수, 채점 입력 토큰, 비용, 총점을 비교한다.
실행: python -X utf8 -I grader/rag_compare.py grader/runs/<full 실험> grader/runs/<rag 실험>
"""
import collections, json, os, sys


def summary(run):
    out = collections.defaultdict(lambda: collections.defaultdict(list))
    for f in sorted(os.listdir(os.path.join(run, "results"))):
        r = json.load(open(os.path.join(run, "results", f), encoding="utf-8"))
        s = out[r["attemptId"]]
        s["total"].append(r["total"])
        s["chars"].append(r.get("referenceDocs", {}).get("chars", 0))
        s["chunks"].append(f'{r.get("referenceDocs", {}).get("chunksUsed")}/{r.get("referenceDocs", {}).get("chunksTotal")}')
        s["in"].append(sum(v["input"] for k, v in r["tokens"].items() if k.startswith("judge_")))
    usd = json.load(open(os.path.join(run, "run.json"), encoding="utf-8")).get("actualUsd")
    return out, usd


(full, fu), (rag, ru) = summary(sys.argv[1]), summary(sys.argv[2])
avg = lambda xs: sum(xs) / len(xs)
for a in full:
    f, r = full[a], rag[a]
    print(f"{a}: 자료 {avg(f['chars']):.0f}자 → {avg(r['chars']):.0f}자 (조각 {r['chunks'][0]}), "
          f"채점 입력 토큰 {avg(f['in']):.0f} → {avg(r['in']):.0f} ({1 - avg(r['in']) / avg(f['in']):.0%} 절감), "
          f"총점 {f['total']} 평균 {avg(f['total']):.1f} → {r['total']} 평균 {avg(r['total']):.1f}")
print(f"비용: ${fu} → ${ru} ({1 - ru / fu:.0%} 절감)")
