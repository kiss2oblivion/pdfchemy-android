"""Deterministic synthetic PDFs; never relabel these as real producer exports."""
import hashlib
import json
from pathlib import Path
import sys

sys.stdout.reconfigure(encoding="utf-8")
ROOT = Path(__file__).resolve().parents[1]
DEST = ROOT / "app-host/src/androidTest/assets/vanguard-benign"


def pdf(features):
    objects = []

    def add(value):
        objects.append(value.encode("ascii") if isinstance(value, str) else value)
        return f"{len(objects)} 0 R"

    def stream(data, extra=""):
        return add(f"<< /Length {len(data)} {extra} >>\nstream\n".encode() + data + b"\nendstream")

    catalog = add("")
    pages = add("")
    page = add("")
    font = add("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>")
    content = stream(b"BT /F1 16 Tf 40 760 Td (Benign Vanguard compatibility fixture) Tj ET")
    cat, annots = [], []
    info = None
    if "metadata" in features:
        info = add("<< /Title (Benign fixture) /Author (PDFchemy regression) /Creator (Synthetic test generator) >>")
    if "xmp" in features:
        xmp = b'<?xpacket begin=""?><x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"><rdf:Description rdf:about="" xmlns:dc="http://purl.org/dc/elements/1.1/" dc:format="application/pdf"/></rdf:RDF></x:xmpmeta><?xpacket end="w"?>'
        cat.append("/Metadata " + stream(xmp, "/Type /Metadata /Subtype /XML"))
    if "https" in features:
        action = add("<< /S /URI /URI (https://example.com/benign) >>")
        annots.append(add(f"<< /Type /Annot /Subtype /Link /Rect [40 700 240 725] /A {action} >>"))
    if "outlines" in features:
        outline = add("")
        link = add("<< /S /URI /URI (https://example.com/bookmark) >>")
        item = add(f"<< /Title (Ordinary HTTPS bookmark) /Parent {outline} /A {link} >>")
        objects[int(outline.split()[0]) - 1] = f"<< /Type /Outlines /First {item} /Last {item} /Count 1 >>".encode()
        cat.append("/Outlines " + outline)
    if "destination" in features:
        cat.append(f"/OpenAction [{page} /Fit]")
    if "goto" in features:
        action = add(f"<< /S /GoTo /D [{page} /Fit] >>")
        annots.append(add(f"<< /Type /Annot /Subtype /Link /Rect [40 660 240 680] /A {action} >>"))
    if "attachment" in features or "c2pa" in features:
        c2pa = "c2pa" in features
        # An inert associated-file carrier, not a cryptographically signed C2PA claim.
        filename = "content-credentials.c2pa" if c2pa else "benign.txt"
        data = b"synthetic inert C2PA carrier" if c2pa else b"ordinary benign attachment"
        embedded = stream(data, "/Type /EmbeddedFile /Subtype /application#2Foctet-stream")
        spec = add(f"<< /Type /Filespec /F ({filename}) /UF ({filename}) /AFRelationship /Data /EF << /F {embedded} /UF {embedded} >> >>")
        names = add(f"<< /Names [({filename}) {spec}] >>")
        cat.append(f"/Names << /EmbeddedFiles {names} >>")
        if "af" in features or c2pa:
            cat.append(f"/AF [{spec}]")
        if "fileannotation" in features:
            annots.append(add(f"<< /Type /Annot /Subtype /FileAttachment /Rect [40 620 60 640] /FS {spec} >>"))
    if "form" in features:
        widget = add(f"<< /Type /Annot /Subtype /Widget /FT /Tx /T (name) /V (Benign form) /Rect [40 580 260 605] /P {page} /DA (/F1 12 Tf 0 g) >>")
        annots.append(widget)
        cat.append(f"/AcroForm << /Fields [{widget}] /DR << /Font << /F1 {font} >> >> /DA (/F1 12 Tf 0 g) /NeedAppearances true >>")
    if "annotation" in features:
        annots.append(add("<< /Type /Annot /Subtype /Text /Rect [40 540 60 560] /Contents (Ordinary note) >>"))
    if "image" in features:
        image = stream(bytes([40, 120, 200]), "/Type /XObject /Subtype /Image /Width 1 /Height 1 /ColorSpace /DeviceRGB /BitsPerComponent 8")
        content = stream(b"BT /F1 16 Tf 40 760 Td (Image-to-PDF fixture) Tj ET\nq 100 0 0 100 40 600 cm /Im1 Do Q")
        image_resource = f"/XObject << /Im1 {image} >>"
    else:
        image_resource = ""
    objects[0] = f"<< /Type /Catalog /Pages {pages} {' '.join(cat)} >>".encode()
    objects[1] = f"<< /Type /Pages /Kids [{page}] /Count 1 >>".encode()
    objects[2] = f"<< /Type /Page /Parent {pages} /MediaBox [0 0 595 842] /Resources << /Font << /F1 {font} >> {image_resource} >> /Contents {content} /Annots [{' '.join(annots)}] >>".encode()
    result = bytearray(b"%PDF-1.7\n%\xe2\xe3\xcf\xd3\n")
    offsets = [0]
    for index, value in enumerate(objects, 1):
        offsets.append(len(result))
        result.extend(f"{index} 0 obj\n".encode() + value + b"\nendobj\n")
    xref = len(result)
    result.extend(f"xref\n0 {len(offsets)}\n0000000000 65535 f \n".encode())
    for offset in offsets[1:]:
        result.extend(f"{offset:010d} 00000 n \n".encode())
    result.extend(f"trailer\n<< /Size {len(offsets)} /Root {catalog} {'/Info ' + info if info else ''} >>\nstartxref\n{xref}\n%%EOF\n".encode())
    return bytes(result)


CASES = [
    ("01-metadata-xmp.pdf", ["metadata", "xmp"], 0, 0),
    ("02-https-link.pdf", ["https"], 0, 1),
    ("03-bookmark-https.pdf", ["outlines"], 0, 1),
    ("04-destination-openaction.pdf", ["destination"], 0, 0),
    ("05-internal-goto.pdf", ["goto"], 0, 0),
    ("06-embedded-file.pdf", ["attachment"], 1, 0),
    ("07-shared-af-annotation.pdf", ["attachment", "af", "fileannotation"], 1, 0),
    ("08-normal-form-annotations.pdf", ["form", "annotation"], 0, 0),
    ("09-image-font.pdf", ["image"], 0, 0),
    ("10-combined-c2pa-carrier.pdf", ["metadata", "xmp", "https", "outlines", "destination", "goto", "c2pa", "af", "fileannotation", "form", "annotation", "image"], 1, 2),
]

if __name__ == "__main__":
    DEST.mkdir(parents=True, exist_ok=True)
    manifest = []
    for name, features, attachments, uris in CASES:
        data = pdf(features)
        (DEST / name).write_bytes(data)
        manifest.append(dict(file=name, provenance="synthetic", features=features,
                             sha256=hashlib.sha256(data).hexdigest(), attachments=attachments, uris=uris))
    existing = DEST / "local-benign-compatibility.pdf"
    if existing.exists():
        manifest.append(dict(file=existing.name, provenance="unchanged local test_pdfs/vanguard_benign_compatibility_test.pdf; original incident provenance unconfirmed",
                             sha256=hashlib.sha256(existing.read_bytes()).hexdigest(), attachments=None, uris=None))
    (DEST / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    print(f"Generated {len(CASES)} synthetic fixtures; corpus entries: {len(manifest)}")
