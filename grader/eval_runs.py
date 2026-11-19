"""채점 실험 결과 분석 (채점 툴의 runs/ 폴더를 읽는다).
- 일관성: 샘플별 반복 채점 총점의 최대-최소 차이
- 정확도: samples/expected.json(샘플을 만든 의도에 따른 정답)과 채점 모델별 판정이 맞는 비율
실행: python -I grader/eval_runs.py grader/runs/<실험 폴더> [...]
"""
import collections, json, os, sys

HERE = os.path.dirname(os.path.abspath(__file__))
EXPECTED = json.load(open(os.path.join(HERE, "samples", "expected.json"), encoding="utf-8"))["expected"]


def load(run):
    out = collections.defaultdict(list)
    d = os.path.join(run, "results")
    for f in sorted(os.listdir(d)):
        r = json.load(open(os.path.join(d, f), encoding="utf-8"))
        out[r["attemptId"]].append(r)
    return out


def checks(r):
    for it in r["items"]:
        for c in it["checks"]:
            yield c


def report(run):
    by = load(run)
    print(f"== {os.path.basename(run.rstrip('/'))}  ({next(iter(by.values()))[0].get('judgeModel')}, {next(iter(by.values()))[0].get('promptVersion')})")
    spreads = []
    for a, rs in by.items():
        tot = [r["total"] for r in rs]
        spreads.append(max(tot) - min(tot))
        print(f"  {a}: 총점 {tot} 차이 {max(tot) - min(tot)}")
    print(f"  일관성: 최대 {max(spreads)}점, 평균 {sum(spreads) / len(spreads):.1f}점")

    # 모델별 정확도: 근거를 대고 걸렸다고 한 판정만 '걸림'으로 센다(채점 코드와 같은 규칙)
    hit = collections.Counter(); n = collections.Counter(); fp = collections.Counter(); fn = collections.Counter()
    combo_hit = combo_n = 0
    for a, rs in by.items():
        exp = EXPECTED.get(a, {})
        for r in rs:
            for c in checks(r):
                if c["code"] not in exp or c["method"] != "JUDGE" or c["status"] == "SKIPPED":
                    continue
                want = exp[c["code"]]
                for j in c.get("judges", []):
                    got = bool(j["violated"] and j["evidence"])
                    n[j["model"]] += 1
                    hit[j["model"]] += got == want
                    fp[j["model"]] += got and not want
                    fn[j["model"]] += want and not got
                got = c["deducted"] > 0
                combo_n += 1
                combo_hit += got == want
    for m in n:
        print(f"  정확도 {m}: {hit[m] / n[m]:.1%} ({hit[m]}/{n[m]}), 잘못 감점 {fp[m]}, 놓침 {fn[m]}")
    if combo_n:
        print(f"  정확도 두 모델 합산(감점 여부): {combo_hit / combo_n:.1%} ({combo_hit}/{combo_n})")
    usd = json.load(open(os.path.join(run, "run.json"), encoding="utf-8")).get("actualUsd")
    print(f"  비용: ${usd} / 채점 {sum(len(v) for v in by.values())}건")


if __name__ == "__main__":
    for run in sys.argv[1:]:
        report(run)
