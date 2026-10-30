"""Repack the pinned public Spark bundle with bare EOS stops only. No weight edits."""
from pathlib import Path
import hashlib, io, json, sys, zlib

sys.path.insert(0, str(Path(__file__).resolve().parent / "model-builder-deps"))
from litert_lm_builder import unpack, pack, litertlm_peek
from litert_lm_builder.runtime.proto import llm_metadata_pb2
from google.protobuf import text_format, json_format

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "models/Spark-X2.5-4B_int4.litertlm"
OUTPUT = ROOT / "models/Spark-X2.5-4B_int4-bbd1.litertlm"
WORK = ROOT / "models/stop-token-work"
EXPECTED = "ff4fd431693086e44d3c02c338791c90c7197068b06190e1cc2f6a655d83c4f4"
EOS = "<｜end▁of▁sentence｜>"

def digest(path, start=0, end=None, decompress=False):
    h=hashlib.sha256()
    with path.open("rb") as stream:
        stream.seek(start)
        if decompress:
            h.update(zlib.decompress(stream.read(end-start)))
        else:
            remaining=(end if end is not None else path.stat().st_size)-start
            while remaining:
                chunk=stream.read(min(4*1024*1024, remaining));assert chunk
                h.update(chunk);remaining-=len(chunk)
    return h.hexdigest()

def sections(path):
    header=litertlm_peek.read_litertlm_header(str(path),io.StringIO())
    table=header.SectionMetadata()
    return [table.Objects(i) for i in range(table.ObjectsLength())]

assert digest(SOURCE)==EXPECTED, "Unexpected upstream file"
existing=OUTPUT.exists()
assert not existing or "--verify-existing" in sys.argv, "Refusing to overwrite derived bundle"
toml=str(WORK/"model.toml") if existing else unpack(str(SOURCE),str(WORK))
metadata=WORK/"LlmMetadataProto.pbtext"
source_section=sections(SOURCE)[0]
with SOURCE.open("rb") as stream:
    stream.seek(source_section.BeginOffset())
    before=llm_metadata_pb2.LlmMetadata.FromString(stream.read(source_section.EndOffset()-source_section.BeginOffset()))
after=llm_metadata_pb2.LlmMetadata();after.CopyFrom(before)
for stop in before.stop_tokens:
    if stop.HasField("token_str"):
        assert stop.token_str.endswith(EOS)
    else:
        assert list(stop.token_ids.ids)==[1]
del after.stop_tokens[:]
after.stop_tokens.add().token_ids.ids.append(1)
after.stop_tokens.add().token_str=EOS
if not existing:
    metadata.write_text(text_format.MessageToString(after),encoding="utf-8")
    pack(toml,str(OUTPUT))

old=sections(SOURCE);new=sections(OUTPUT)
assert len(old)==len(new)==4
verification=[]
for index,(a,b) in enumerate(zip(old,new)):
    assert a.DataType()==b.DataType()
    assert [litertlm_peek.kvp_to_dict(a.Items(j)) for j in range(a.ItemsLength())]==[litertlm_peek.kvp_to_dict(b.Items(j)) for j in range(b.ItemsLength())]
    if index==0:
        with OUTPUT.open("rb") as stream:
            stream.seek(b.BeginOffset());actual=llm_metadata_pb2.LlmMetadata.FromString(stream.read(b.EndOffset()-b.BeginOffset()))
        assert actual==after
        stripped_old=llm_metadata_pb2.LlmMetadata();stripped_old.CopyFrom(before);del stripped_old.stop_tokens[:]
        stripped_new=llm_metadata_pb2.LlmMetadata();stripped_new.CopyFrom(actual);del stripped_new.stop_tokens[:]
        assert stripped_old==stripped_new
        verification.append({"section":index,"metadataOnlyStopTokensChanged":True})
    else:
        original=digest(SOURCE,a.BeginOffset(),a.EndOffset())
        derived=digest(OUTPUT,b.BeginOffset(),b.EndOffset())
        assert original==derived
        verification.append({"section":index,"type":"raw HF tokenizer section" if index==1 else "TFLite weights","sha256":original,"identical":True})
report={"builderVersion":"0.18.0","upstream":{"file":SOURCE.name,"bytes":SOURCE.stat().st_size,"sha256":EXPECTED},"derived":{"file":OUTPUT.name,"bytes":OUTPUT.stat().st_size,"sha256":digest(OUTPUT)},"change":"Remove punctuation-prefixed EOS stop sequences; retain EOS token ID 1 and the bare EOS string only", "stopsBefore":[json_format.MessageToDict(x) for x in before.stop_tokens],"stopsAfter":[json_format.MessageToDict(x) for x in after.stop_tokens],"sectionVerification":verification,"license":"Apache-2.0; metadata-only derivative of litert-community/Spark-X2.5-4B"}
(ROOT/"models/model-repack-provenance.json").write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding="utf-8")
print(json.dumps(report["derived"]))
