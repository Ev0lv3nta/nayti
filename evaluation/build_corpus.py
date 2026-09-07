"""Build the public-input evaluation corpus outside Git; no private media accepted."""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import random
import urllib.request
import zipfile

ANNOTATIONS_SHA256 = "113a836d90195ee1f884e704da6304dfaaecff1f023f49b6ca93c4aaae470268"
ANNOTATIONS_URL = "https://s3.amazonaws.com/images.cocodataset.org/annotations/annotations_trainval2017.zip"
CATEGORIES = {
    "dog": "собака", "cat": "кошка", "horse": "лошадь", "bird": "птица",
    "bicycle": "велосипед", "car": "машина", "bus": "автобус", "train": "поезд",
    "boat": "лодка", "airplane": "самолёт", "elephant": "слон", "zebra": "зебра",
    "giraffe": "жираф", "pizza": "пицца", "cake": "торт", "banana": "банан",
    "laptop": "ноутбук", "clock": "часы", "book": "книга", "umbrella": "зонт",
}
HOLDOUT = {"airplane", "zebra", "cake", "clock", "umbrella"}
DOCUMENTS = [
    ("ЧЕК ИЗ АПТЕКИ", "Аптека Листок", "Заказ RX-482931", "Итого 1240 рублей", "покупка лекарств", "аптека листок", "аптека листк"),
    ("ЖЕЛЕЗНОДОРОЖНЫЙ БИЛЕТ", "Москва — Казань", "Билет TK-793152", "Отправление 18.11.2026", "поездка на поезде", "москва казань", "москв казань"),
    ("ДОСТАВКА ПОСЫЛКИ", "Пункт выдачи на Садовой", "Трек PK-651824", "Получить до 21 ноября", "забрать заказ из пункта выдачи", "пункт выдачи", "пунк выдачи"),
    ("ДОМАШНЯЯ СЕТЬ", "Пароль от Wi-Fi", "Код WF-928461", "Сеть: Nayti-Test", "подключение к домашнему интернету", "пароль от wi-fi", "парол от wi-fi"),
    ("ЗАПИСЬ НА ОБСЛУЖИВАНИЕ", "Автосервис Мотор", "Заявка AS-374629", "Замена масла в пятницу", "техническое обслуживание автомобиля", "замена масла", "замена масл"),
    ("БРОНИРОВАНИЕ ОТЕЛЯ", "Гостиница Берег", "Бронь HT-582713", "Заезд 12 декабря", "где остановиться во время поездки", "гостиница берег", "гостиниц берег"),
    ("РЕЦЕПТ ПИРОГА", "Яблоки и корица", "Рецепт RC-416938", "Выпекать 40 минут", "как приготовить десерт из яблок", "яблоки и корица", "яблки и корица"),
    ("КВИТАНЦИЯ ЗА КВАРТИРУ", "Оплата электричества", "Счёт EL-863147", "К оплате 2380 рублей", "коммунальные расходы за свет", "оплата электричества", "оплата электричства"),
    ("УЧЕБНОЕ РАСПИСАНИЕ", "Курс фотографии", "Группа ED-719352", "Занятие в среду в 19:00", "когда начинается обучение съёмке", "курс фотографии", "курс фотограии"),
    ("КОНЦЕРТНЫЙ БИЛЕТ", "Вечер камерной музыки", "Билет MU-245819", "Ряд 7 место 12", "послушать живую музыку", "камерной музыки", "камерной музки"),
]


def digest(path: Path) -> str:
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def fetch(url: str, path: Path, limit: int = 12 * 1024 * 1024) -> None:
    if path.exists():
        return
    temporary = path.with_suffix(path.suffix + ".partial")
    try:
        with urllib.request.urlopen(url, timeout=45) as source, temporary.open("wb") as target:
            count = 0
            while chunk := source.read(65536):
                count += len(chunk)
                if count > limit:
                    raise ValueError("Download exceeds corpus input size cap")
                target.write(chunk)
        temporary.replace(path)
    finally:
        temporary.unlink(missing_ok=True)


def build(annotations: Path, output: Path, font: Path) -> dict:
    import PIL
    from PIL import Image, ImageDraw, ImageFont

    if output.resolve().is_relative_to(Path(__file__).resolve().parents[1]):
        raise ValueError("Keep images outside the Git repository")
    if PIL.__version__ != "12.3.0":
        raise ValueError("Pinned corpus rendering requires Pillow 12.3.0")
    if digest(annotations) != ANNOTATIONS_SHA256:
        raise ValueError("COCO annotation archive identity mismatch")
    output.mkdir(parents=True, exist_ok=True)
    if (output / "manifest.json").exists():
        raise ValueError("Refusing to overwrite a completed corpus")
    with zipfile.ZipFile(annotations) as archive:
        data = json.loads(archive.read("annotations/instances_val2017.json"))
    images = {item["id"]: item for item in data["images"] if item["license"] in (4, 5)}
    names = {item["id"]: item["name"] for item in data["categories"]}
    labels: dict[int, set[str]] = {key: set() for key in images}
    areas: dict[tuple[int, str], float] = {}
    for annotation in data["annotations"]:
        key = annotation["image_id"]
        if key not in images:
            continue
        name = names[annotation["category_id"]]
        labels[key].add(name)
        area = annotation["area"] / (images[key]["width"] * images[key]["height"])
        areas[key, name] = max(areas.get((key, name), 0), area)
    selected: dict[int, str] = {}
    for name in CATEGORIES:
        candidates = sorted(
            (key for key in images if key not in selected and areas.get((key, name), 0) >= 0.02),
            key=lambda key: (-areas[key, name], key),
        )
        if len(candidates) < 6:
            raise ValueError(f"Insufficient licensed examples for {name}")
        for key in candidates[:6]:
            selected[key] = "holdout" if name in HOLDOUT else "development"
    remaining = sorted(set(images) - selected.keys())
    random.Random(20260908).shuffle(remaining)
    for index, key in enumerate(remaining[:30]):
        selected[key] = "holdout" if index < 8 else "development"
    licenses = {item["id"]: item for item in data["licenses"]}
    assets = []
    for key, split in selected.items():
        original = images[key]
        asset_id = f"coco-{key:012d}"
        relative = f"{split}/{asset_id}.jpg"
        destination = output / relative
        destination.parent.mkdir(exist_ok=True)
        source = f"https://s3.amazonaws.com/images.cocodataset.org/val2017/{key:012d}.jpg"
        fetch(source, destination)
        with Image.open(destination) as decoded:
            decoded.verify()
        assets.append({
            "id": asset_id, "family": asset_id, "split": split, "file": relative,
            "sha256": digest(destination), "labels": sorted(labels[key]), "kind": "photo",
            "source": source, "original": original["flickr_url"],
            "attribution": "Original Flickr contributor; see original source link. COCO 2017 annotation snapshot.",
            "license": licenses[original["license"]]["url"].replace("http:", "https:"),
        })
    font_regular = ImageFont.truetype(str(font), 35)
    font_title = ImageFont.truetype(str(font), 44)
    for index, document in enumerate(DOCUMENTS):
        split = "holdout" if index >= 7 else "development"
        family = f"document-{index:02d}"
        for variant in range(3):
            canvas = Image.new("RGB", (960, 1280), (245, 242, 234))
            draw = ImageDraw.Draw(canvas)
            draw.rounded_rectangle((35, 45, 925, 1210), 16, fill=(255, 255, 255), outline=(140, 140, 140))
            for line, text in enumerate(document[:4]):
                draw.text((65, 105 + line * 135), text, font=font_title if line == 0 else font_regular, fill=(25, 25, 25))
            draw.text((65, 1000), "Синтетический пример Nayti\nНе является настоящим документом", font=font_regular, fill=(90, 90, 90))
            if variant == 1:
                canvas = canvas.resize((720, 960))
            elif variant == 2:
                canvas = canvas.rotate(2, resample=Image.Resampling.BICUBIC, fillcolor=(220, 220, 220))
            asset_id = f"{family}-{variant}"
            relative = f"{split}/{asset_id}.jpg"
            destination = output / relative
            canvas.save(destination, quality=95 if variant == 0 else 80)
            assets.append({"id": asset_id, "family": family, "split": split, "file": relative,
                           "sha256": digest(destination), "labels": [], "kind": "document",
                           "source": "Nayti synthetic recipe v1", "license": "CC0-1.0", "text": list(document[:4])})
    queries = []
    def add(category, text, split, relevant, channels, **extra):
        queries.append({"id": f"q{len(queries):03d}", "category": category, "query": text,
                        "split": split, "relevant": sorted(relevant), "channels": channels, **extra})
    for name, russian in CATEGORIES.items():
        split = "holdout" if name in HOLDOUT else "development"
        relevant = [a["id"] for a in assets if a["split"] == split and name in a["labels"]]
        for text in (russian, name, f"На фотографии {russian}"):
            add("visual", text, split, relevant, ["visual"])
    for index, document in enumerate(DOCUMENTS):
        split = "holdout" if index >= 7 else "development"
        family = f"document-{index:02d}"
        relevant = [a["id"] for a in assets if a["family"] == family]
        add("identifier", document[2].split()[-1], split, relevant, ["literal", "semantic", "visual"])
        add("phrase", document[5], split, relevant, ["literal"])
        add("typo", document[6], split, relevant, ["literal"])
        add("document_semantics", document[4], split, relevant, ["semantic"])
        add("duplicates", "", split, relevant[1:], [], source_asset=relevant[0])
    for index, text in enumerate(("ZZ-NAYTI-000000", "QX-NEVER-819573", "Счёт ZZ-000000", "NAYTI-ABSENT-725190", "XX-NO-592641", "ZZ-MISSING-312987")):
        add("negative_identifier", text, "holdout" if index >= 4 else "development", [], ["literal", "semantic", "visual"])
    manifest = {"schema": 1, "corpus": "nayti-public-v1", "annotations_sha256": ANNOTATIONS_SHA256,
                "annotations_source": ANNOTATIONS_URL, "annotations_license": "CC-BY-4.0 (COCO Consortium)",
                "font_sha256": digest(font), "assets": assets, "queries": queries,
                "redistribution": "Images stay outside Git. Recheck source attribution and licensing before any redistribution.",
                "label_limits": "COCO object-presence labels are not exhaustive scene/action/caption judgements. Negative queries test absent synthetic identifiers, not general visual rejection."}
    hashes = [a["sha256"] for a in assets]
    if len(hashes) != len(set(hashes)):
        raise ValueError("Duplicate image bytes; audit families before accepting corpus")
    (output / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n")
    return manifest


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--annotations", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--font", type=Path, required=True)
    args = parser.parse_args()
    result = build(args.annotations, args.output, args.font)
    print(f"Created {len(result['assets'])} images and {len(result['queries'])} queries")
