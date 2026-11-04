import unittest

from check_release_gson_contract import (parse_class_dump, validate_text_contract,
                                       TEXT_OPTIONS, TEXT_OPTION_FIELDS, TEXT_ENUMS)


class DexdumpSchemaParserTest(unittest.TestCase):
    def test_class_annotations_before_descriptor_are_joined_by_class_number(self):
        text = '''Class #12 annotations:
Annotations on class
  VISIBILITY_SYSTEM Ldalvik/annotation/Signature; value={ "LToken<" "Ljava/lang/String;" ">;" }
Class #12            -
  Class descriptor  : 'LChild;'
  Superclass        : 'LToken;'
  Static fields     -
  Instance fields   -
    #0              : (in LChild;)
      name          : 'items'
      type          : 'Ljava/util/List;'
  Direct methods    -
      name          : 'methodIsNotAField'
'''
        row = parse_class_dump(text)["LChild;"]
        self.assertEqual("LToken<Ljava/lang/String;>;", row["signature"])
        self.assertEqual("LToken;", row["superclass"])
        self.assertEqual({"items"}, row["fields"])

    def test_field_signature_does_not_substitute_for_class_signature(self):
        text = '''Class #3 annotations:
Annotations on field #0 'items'
  VISIBILITY_SYSTEM Ldalvik/annotation/Signature; value={ "Ljava/util/List<Ljava/lang/String;>;" }
Class #3            -
  Class descriptor  : 'LRawChild;'
  Superclass        : 'LToken;'
  Static fields     -
  Instance fields   -
  Direct methods    -
'''
        row = parse_class_dump(text)["LRawChild;"]
        self.assertIsNone(row["signature"])
        self.assertEqual({"items": "Ljava/util/List<Ljava/lang/String;>;"}, row["field_signatures"])

    def test_annotations_on_next_class_do_not_leak_backwards(self):
        text = '''Class #0 -
  Class descriptor  : 'LFirst;'
  Superclass        : 'Ljava/lang/Object;'
Class #1 annotations:
Annotations on class
  VISIBILITY_SYSTEM Ldalvik/annotation/Signature; value={ "LToken<Ljava/lang/Integer;>;" }
Class #1 -
  Class descriptor  : 'LSecond;'
  Superclass        : 'LToken;'
'''
        rows = parse_class_dump(text)
        self.assertIsNone(rows["LFirst;"]["signature"])
        self.assertEqual("LToken<Ljava/lang/Integer;>;", rows["LSecond;"]["signature"])

    def test_static_enum_fields_are_included(self):
        text = '''Class #0 -
  Class descriptor  : 'LEnum;'
  Static fields     -
    #0              : (in LEnum;)
      name          : 'APPROVED'
  Instance fields   -
  Direct methods    -
'''
        self.assertEqual({"APPROVED"}, parse_class_dump(text)["LEnum;"]["fields"])

    def test_windows_sdk_newlines_preserve_signatures_and_fields(self):
        text = "\r\n".join([
            "Class #0 annotations:", "Annotations on class",
            '  VISIBILITY_SYSTEM Ldalvik/annotation/Signature; value={ "LToken<Ljava/lang/String;>;" }',
            "Class #0 -", "  Class descriptor  : 'LWindows;'", "  Superclass        : 'LToken;'",
            "  Static fields     -", "  Instance fields   -", "      name          : 'items'",
            "  Direct methods    -", "",
        ])
        row = parse_class_dump(text)["LWindows;"]
        self.assertEqual("LToken<Ljava/lang/String;>;", row["signature"])
        self.assertEqual({"items"}, row["fields"])


class TextReflectionContractTest(unittest.TestCase):
    def preserved(self):
        return {name: {"fields": fields.copy()} for name, fields in TEXT_ENUMS.items()}

    def test_complete_reflected_enum_is_accepted(self):
        result = validate_text_contract(self.preserved())
        self.assertEqual(7, result["reflected_enums"])
        self.assertEqual(30, result["reflected_enum_fields"])

    def test_obfuscated_enum_class_is_rejected(self):
        classes = self.preserved()
        classes["Lb43;"] = classes.pop(TEXT_OPTIONS)
        with self.assertRaisesRegex(RuntimeError, "enum missing/renamed"):
            validate_text_contract(classes)

    def test_renamed_field_reproduces_signed_initializer_failure(self):
        fields = TEXT_OPTION_FIELDS - {"TRIM_LEADING_EOL"} | {"X"}
        classes = self.preserved(); classes[TEXT_OPTIONS]["fields"] = fields
        with self.assertRaisesRegex(RuntimeError, "TRIM_LEADING_EOL"):
            validate_text_contract(classes)

    def test_shrunk_unused_option_is_rejected(self):
        classes = self.preserved(); classes[TEXT_OPTIONS]["fields"].remove("PREFIX_PRE_FORMATTED")
        with self.assertRaisesRegex(RuntimeError, "PREFIX_PRE_FORMATTED"):
            validate_text_contract(classes)

    def test_each_bit_field_enum_rejects_missing_class(self):
        for name in TEXT_ENUMS:
            with self.subTest(enum=name):
                classes = self.preserved(); del classes[name]
                with self.assertRaisesRegex(RuntimeError, "enum missing/renamed"):
                    validate_text_contract(classes)

    def test_each_bit_field_enum_rejects_renamed_constant(self):
        for name, fields in TEXT_ENUMS.items():
            with self.subTest(enum=name):
                classes = self.preserved(); classes[name]["fields"].remove(sorted(fields)[0])
                classes[name]["fields"].add("X")
                with self.assertRaisesRegex(RuntimeError, "fields renamed/removed"):
                    validate_text_contract(classes)


if __name__ == "__main__":
    unittest.main()
