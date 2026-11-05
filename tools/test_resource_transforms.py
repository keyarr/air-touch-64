import unittest

from resource_transforms import apply


class ResourceTransformsTest(unittest.TestCase):
    def test_catalog_preserves_encoding_line_endings_and_unrelated_lines(self):
        source = '"$$$/TTGallery/Adobe Photoshop Touch=Original"\r\n"keep=value"\r\n'.encode("utf-16")
        result = apply("assets/resource/application/en/application.zstrings", source)
        self.assertEqual(
            '"$$$/TTGallery/Adobe Photoshop Touch=Ps Touch"\r\n"keep=value"\r\n'
            '"$$$/private/GoURL/FacebookPage=https://github.com/keyarr/air-touch-64"\n',
            result.decode("utf-16"),
        )

    def test_catalog_requires_expected_key_count(self):
        with self.assertRaisesRegex(ValueError, "expected one catalog key"):
            apply("assets/resource/application/en/application.zstrings", '"keep=value"\n'.encode("utf-16"))


if __name__ == "__main__":
    unittest.main()
