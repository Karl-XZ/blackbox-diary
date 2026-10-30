"""Evaluate the V1.1 human-annotation gates without inventing or self-labeling data."""
import argparse, csv, json
from collections import Counter
from pathlib import Path

def rows(path):
    with Path(path).open(encoding="utf-8-sig", newline="") as stream:
        return list(csv.DictReader(stream))

def evaluate(directory):
    root=Path(directory)
    scenarios=rows(root/"scenarios.csv"); claims=rows(root/"claims.csv"); events=rows(root/"important-events.csv")
    errors=[]
    minimum={"SCREEN":30,"AUDIO":30,"COVERAGE_CLOCK":20,"DELETE_CONCURRENCY":20,"INJECTION_PRIVACY":20}
    count=Counter(row["category"] for row in scenarios)
    for category,n in minimum.items():
        if count[category]<n: errors.append(f"SCENARIOS_{category}: {count[category]}/{n}")
    if len({row["scenario_id"] for row in scenarios})!=len(scenarios):errors.append("DUPLICATE_SCENARIO_ID")
    if len(claims)<1000: errors.append(f"CLAIM_SAMPLE: {len(claims)}/1000")
    if len({row["claim_id"] for row in claims})!=len(claims):errors.append("DUPLICATE_CLAIM_ID")
    scenario_ids={row["scenario_id"] for row in scenarios}
    supported=structure=high=high_correct=severe=privacy=0
    for row in claims:
        if row["scenario_id"] not in scenario_ids:errors.append("UNKNOWN_SCENARIO")
        a,b=row["reviewer_a_support"],row["reviewer_b_support"]
        allowed={"FULL","PARTIAL","NONE"}
        if a not in allowed or b not in allowed: errors.append("TWO_INDEPENDENT_REVIEWERS_REQUIRED");continue
        support=a if a==b else row["adjudicated_support"]
        if support not in allowed:errors.append("ADJUDICATION_REQUIRED");continue
        if not row["reviewer_a_id"] or not row["reviewer_b_id"] or row["reviewer_a_id"]==row["reviewer_b_id"]:errors.append("INDEPENDENT_REVIEWER_IDS_REQUIRED")
        if a!=b and (not row["adjudicator_id"] or row["adjudicator_id"] in (row["reviewer_a_id"],row["reviewer_b_id"])):errors.append("THIRD_REVIEWER_REQUIRED")
        supported+=support=="FULL";structure+=row["structure_pass"]=="1"
        if row["quality"]=="HIGH":high+=1;high_correct+=row["original_material_support"]=="FULL"
        severe+=row["severe_unsupported"]=="1";privacy+=row["privacy_leak"]=="1"
    important=[r for r in events if r["important"]=="1"]
    retained=sum(r["covered_by_fact_log"]=="1" for r in important)
    ratio=lambda a,b: a/b if b else None
    metrics={"structureCorrectness":ratio(structure,len(claims)),"evidenceSupport":ratio(supported,len(claims)),"highEndToEndAccuracy":ratio(high_correct,high),"importantEventRetention":ratio(retained,len(important)),"severeUnsupported":severe,"privacyLeaks":privacy}
    thresholds={"structureCorrectness":1.0,"evidenceSupport":.99,"highEndToEndAccuracy":.95,"importantEventRetention":.90}
    for key,threshold in thresholds.items():
        if metrics[key] is None or metrics[key]<threshold:errors.append(f"GATE_{key}")
    if severe:errors.append("SEVERE_UNSUPPORTED_BLOCKER")
    if privacy:errors.append("PRIVACY_BLOCKER")
    return {"passed":not errors,"claims":len(claims),"scenarioCounts":dict(count),"metrics":metrics,"errors":sorted(set(errors)),"note":"Human evaluation only; source/citation validation, deletion resurrection, ledger conservation and hardware gates require their separate test reports."}

if __name__=="__main__":
    parser=argparse.ArgumentParser();parser.add_argument("directory");parser.add_argument("--out");args=parser.parse_args()
    result=evaluate(args.directory);output=json.dumps(result,ensure_ascii=False,indent=2)
    if args.out:Path(args.out).write_text(output,encoding="utf-8")
    print(output)
    raise SystemExit(0 if result["passed"] else 2)
