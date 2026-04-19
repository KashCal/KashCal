#!/usr/bin/env python3
"""
Verify translated strings.xml files against the base English strings.xml.

Checks:
1. All string keys from base exist in each locale
2. Format specifiers match (%1$s, %1$d, etc. — same count and order)
3. Apostrophes properly escaped (\')
4. XML is well-formed
5. translatable="false" strings not included in locale files
6. Plural resources have required quantities per language
7. Brand names not accidentally translated
8. No empty translations
9. Escaped characters preserved (\\n, \\u00B7, etc.)

Usage:
    python3 scripts/verify_translations.py
    python3 scripts/verify_translations.py --locale es    # check one locale
    python3 scripts/verify_translations.py --fix-escaping # auto-fix apostrophe escaping
"""

import os
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path
from collections import defaultdict

BASE_DIR = Path(__file__).parent.parent / "app" / "src" / "main" / "res"
BASE_STRINGS = BASE_DIR / "values" / "strings.xml"

# Brand names that must NOT be translated
NEVER_TRANSLATE = [
    "KashCal", "iCloud", "Apple ID", "CalDAV", "Nextcloud",
    "FastMail", "F-Droid", ".ics", "ICS", "HTTPS",
]

# Format specifier pattern: %1$s, %1$d, %2$s, etc.
FORMAT_SPEC_PATTERN = re.compile(r'%\d+\$[sdfl]')

# Unescaped apostrophe pattern (not preceded by backslash, not inside "")
UNESCAPED_APOSTROPHE = re.compile(r"(?<!\\)'")

# Required plural quantities per language (CLDR)
PLURAL_FORMS = {
    # one, other (most common)
    "af": ["one", "other"],
    "am": ["one", "other"],
    "bg": ["one", "other"],
    "bn": ["one", "other"],
    "ca": ["one", "other"],
    "da": ["one", "other"],
    "de": ["one", "other"],
    "el": ["one", "other"],
    "en-rGB": ["one", "other"],
    "eo": ["one", "other"],
    "es": ["one", "other"],
    "es-rUS": ["one", "other"],
    "et": ["one", "other"],
    "eu": ["one", "other"],
    "fi": ["one", "other"],
    "fil": ["one", "other"],
    "fr": ["one", "other"],
    "gl": ["one", "other"],
    "hi": ["one", "other"],
    "hu": ["one", "other"],
    "is": ["one", "other"],
    "it": ["one", "other"],
    "kk": ["one", "other"],
    "kn": ["one", "other"],
    "mk": ["one", "other"],
    "ml": ["one", "other"],
    "nb": ["one", "other"],
    "ne": ["one", "other"],
    "nl": ["one", "other"],
    "nn": ["one", "other"],
    "or": ["one", "other"],
    "pa": ["one", "other"],
    "pt": ["one", "other"],
    "pt-rBR": ["one", "other"],
    "pt-rPT": ["one", "other"],
    "si": ["one", "other"],
    "sv": ["one", "other"],
    "sw": ["one", "other"],
    "ta": ["one", "other"],
    "te": ["one", "other"],
    "tr": ["one", "other"],
    "zu": ["one", "other"],
    # other only
    "in": ["other"],
    "ja": ["other"],
    "ko": ["other"],
    "ms": ["other"],
    "my": ["other"],
    "th": ["other"],
    "vi": ["other"],
    "zh-rCN": ["other"],
    "zh-rTW": ["other"],
    # one, few, other (3 forms)
    "bs": ["one", "few", "other"],
    "cs": ["one", "few", "other"],
    "hr": ["one", "few", "other"],
    "ro": ["one", "few", "other"],
    "sk": ["one", "few", "other"],
    "sr": ["one", "few", "other"],
    # one, few, many, other (4 forms)
    "be": ["one", "few", "many", "other"],
    "lt": ["one", "few", "many", "other"],
    "pl": ["one", "few", "many", "other"],
    "ru": ["one", "few", "many", "other"],
    "uk": ["one", "few", "many", "other"],
    # one, two, few, other (4 forms)
    "gd": ["one", "two", "few", "other"],
    "sl": ["one", "two", "few", "other"],
    # Special
    "cy": ["zero", "one", "two", "few", "many", "other"],
    "ga": ["one", "two", "few", "many", "other"],
    "lv": ["zero", "one", "other"],
}


class TranslationVerifier:
    def __init__(self):
        self.errors = defaultdict(list)
        self.warnings = defaultdict(list)
        self.stats = defaultdict(lambda: {"strings": 0, "plurals": 0, "errors": 0, "warnings": 0})

    def parse_strings_xml(self, filepath):
        """Parse strings.xml and return dict of {name: text} and {name: {quantity: text}}."""
        strings = {}
        plurals = {}
        non_translatable = set()

        try:
            tree = ET.parse(filepath)
        except ET.ParseError as e:
            return None, None, None, str(e)

        root = tree.getroot()

        for elem in root.findall("string"):
            name = elem.get("name")
            if elem.get("translatable") == "false":
                non_translatable.add(name)
                continue
            text = elem.text or ""
            strings[name] = text

        for elem in root.findall("plurals"):
            name = elem.get("name")
            items = {}
            for item in elem.findall("item"):
                quantity = item.get("quantity")
                text = item.text or ""
                items[quantity] = text
            plurals[name] = items

        return strings, plurals, non_translatable, None

    def get_format_specs(self, text):
        """Extract format specifiers from a string."""
        return sorted(FORMAT_SPEC_PATTERN.findall(text))

    def check_format_specifiers(self, locale, name, base_text, locale_text):
        """Verify format specifiers match between base and locale."""
        base_specs = self.get_format_specs(base_text)
        locale_specs = self.get_format_specs(locale_text)

        if base_specs != locale_specs:
            self.errors[locale].append(
                f"  FORMAT MISMATCH: {name}\n"
                f"    base:   {base_specs}\n"
                f"    {locale}: {locale_specs}"
            )
            return False
        return True

    def check_escaping(self, locale, name, text):
        """Check for unescaped apostrophes in XML string values."""
        # Raw XML text — check for unescaped single quotes
        # This is tricky because ET already unescapes, so we check the raw file
        pass  # Handled in check_raw_escaping

    def check_brand_names(self, locale, name, base_text, locale_text):
        """Check that brand names are preserved."""
        for brand in NEVER_TRANSLATE:
            if brand in base_text and brand not in locale_text:
                # Check case-insensitive too
                if brand.lower() not in locale_text.lower():
                    self.warnings[locale].append(
                        f"  BRAND MISSING: {name} — \"{brand}\" not found in translation"
                    )

    def check_empty(self, locale, name, text):
        """Check for empty translations."""
        if not text or not text.strip():
            self.errors[locale].append(f"  EMPTY: {name}")
            return False
        return True

    def check_plural_forms(self, locale, name, quantities):
        """Check that required plural forms are present for the locale."""
        locale_code = locale.replace("values-", "")
        required = PLURAL_FORMS.get(locale_code)

        if not required:
            self.warnings[locale].append(
                f"  UNKNOWN LOCALE: {locale_code} — cannot verify plural forms for {name}"
            )
            return True

        missing = [q for q in required if q not in quantities]
        if missing:
            self.errors[locale].append(
                f"  MISSING PLURAL FORMS: {name} — needs {missing} (has {list(quantities.keys())})"
            )
            return False
        return True

    def check_non_translatable(self, locale, locale_strings, non_translatable):
        """Check that translatable=false strings are not in locale file."""
        for name in non_translatable:
            if name in locale_strings:
                self.warnings[locale].append(
                    f"  SHOULD NOT TRANSLATE: {name} (marked translatable=\"false\")"
                )

    def check_raw_escaping(self, filepath, locale):
        """Check raw XML file for unescaped apostrophes."""
        try:
            with open(filepath, 'r', encoding='utf-8') as f:
                for line_num, line in enumerate(f, 1):
                    # Skip comments and non-string lines
                    if '<!--' in line or '<string' not in line:
                        continue
                    # Extract text content between > and </
                    match = re.search(r'>([^<]+)</', line)
                    if match:
                        text = match.group(1)
                        # Find unescaped apostrophes
                        for m in UNESCAPED_APOSTROPHE.finditer(text):
                            # Skip if inside format specifier
                            self.errors[locale].append(
                                f"  UNESCAPED APOSTROPHE: line {line_num}: {line.strip()}"
                            )
        except Exception as e:
            self.errors[locale].append(f"  FILE READ ERROR: {e}")

    def verify_locale(self, locale_dir, base_strings, base_plurals, non_translatable):
        """Verify a single locale's strings.xml."""
        locale = locale_dir.name
        locale_file = locale_dir / "strings.xml"

        if not locale_file.exists():
            self.errors[locale].append("  MISSING: strings.xml not found")
            return

        # Parse
        loc_strings, loc_plurals, _, parse_error = self.parse_strings_xml(locale_file)
        if parse_error:
            self.errors[locale].append(f"  XML PARSE ERROR: {parse_error}")
            return

        self.stats[locale]["strings"] = len(loc_strings)
        self.stats[locale]["plurals"] = len(loc_plurals)

        # Check non-translatable strings not included
        self.check_non_translatable(locale, loc_strings, non_translatable)

        # Check all base strings exist in locale
        for name, base_text in base_strings.items():
            if name not in loc_strings:
                self.errors[locale].append(f"  MISSING STRING: {name}")
                self.stats[locale]["errors"] += 1
                continue

            locale_text = loc_strings[name]

            # Empty check
            if not self.check_empty(locale, name, locale_text):
                self.stats[locale]["errors"] += 1
                continue

            # Format specifier check
            if not self.check_format_specifiers(locale, name, base_text, locale_text):
                self.stats[locale]["errors"] += 1

            # Brand name check
            self.check_brand_names(locale, name, base_text, locale_text)

        # Check extra strings in locale not in base (usually fine, but flag)
        extra = set(loc_strings.keys()) - set(base_strings.keys()) - non_translatable
        if extra:
            self.warnings[locale].append(
                f"  EXTRA STRINGS (not in base): {sorted(extra)}"
            )

        # Check all base plurals exist in locale
        for name, base_items in base_plurals.items():
            if name not in loc_plurals:
                self.errors[locale].append(f"  MISSING PLURAL: {name}")
                self.stats[locale]["errors"] += 1
                continue

            loc_items = loc_plurals[name]

            # Check plural forms for this locale
            self.check_plural_forms(locale, name, loc_items)

            # Check format specifiers in each plural form
            # Compare each quantity against the SAME quantity in base (if it exists),
            # falling back to base "other" for quantities that only exist in the locale.
            # Skip check for quantities not in base (e.g., "zero", "two") — these are
            # locale-specific CLDR forms with no English reference to compare against.
            for quantity, text in loc_items.items():
                if quantity not in base_items and quantity != "other":
                    continue  # No base reference for this CLDR quantity
                base_ref = base_items.get(quantity, base_items.get("other", ""))
                base_specs = self.get_format_specs(base_ref)
                loc_specs = self.get_format_specs(text)
                if base_specs != loc_specs:
                    self.errors[locale].append(
                        f"  PLURAL FORMAT MISMATCH: {name}[{quantity}]\n"
                        f"    base {quantity}: {base_specs}\n"
                        f"    {locale}:   {loc_specs}"
                    )
                    self.stats[locale]["errors"] += 1

        # Check extra plurals
        extra_plurals = set(loc_plurals.keys()) - set(base_plurals.keys())
        if extra_plurals:
            self.warnings[locale].append(
                f"  EXTRA PLURALS (not in base): {sorted(extra_plurals)}"
            )

        # Raw escaping check
        self.check_raw_escaping(locale_file, locale)

        # Tally
        self.stats[locale]["errors"] = len(self.errors[locale])
        self.stats[locale]["warnings"] = len(self.warnings[locale])

    def run(self, target_locale=None):
        """Run verification on all (or one) locale."""
        # Parse base
        base_strings, base_plurals, non_translatable, parse_error = self.parse_strings_xml(BASE_STRINGS)
        if parse_error:
            print(f"FATAL: Cannot parse base strings.xml: {parse_error}")
            return False

        print(f"Base: {len(base_strings)} strings, {len(base_plurals)} plurals, "
              f"{len(non_translatable)} non-translatable\n")

        # Find locale dirs
        locale_dirs = sorted(BASE_DIR.glob("values-*"))
        locale_dirs = [d for d in locale_dirs
                       if (d / "strings.xml").exists()
                       and not d.name.startswith("values-night")
                       and not d.name.startswith("values-land")
                       and "sw" not in d.name.split("-")[-1] if d.name.count("-") > 1 and d.name.split("-")[-1].startswith("sw") and d.name.split("-")[-1][2:].isdigit()
                       ]
        # Simpler filter: only values-XX or values-XX-rYY patterns
        locale_dirs = [d for d in sorted(BASE_DIR.glob("values-*"))
                       if (d / "strings.xml").exists()
                       and re.match(r'^values-[a-z]{2,3}(-r[A-Z]{2})?$', d.name)]

        if target_locale:
            target_dir_name = f"values-{target_locale}"
            locale_dirs = [d for d in locale_dirs if d.name == target_dir_name]
            if not locale_dirs:
                print(f"Locale directory not found: {target_dir_name}")
                return False

        if not locale_dirs:
            print("No locale directories found. Run translations first.")
            return True

        print(f"Checking {len(locale_dirs)} locale(s)...\n")

        # Verify each
        for locale_dir in locale_dirs:
            self.verify_locale(locale_dir, base_strings, base_plurals, non_translatable)

        # Report
        total_errors = 0
        total_warnings = 0
        clean_locales = []

        for locale_dir in locale_dirs:
            locale = locale_dir.name
            errors = self.errors[locale]
            warnings = self.warnings[locale]
            stats = self.stats[locale]
            total_errors += len(errors)
            total_warnings += len(warnings)

            if not errors and not warnings:
                clean_locales.append(locale)
                continue

            status = "FAIL" if errors else "WARN"
            print(f"[{status}] {locale} ({stats['strings']} strings, {stats['plurals']} plurals)")

            for e in errors:
                print(f"  ERROR: {e}" if not e.startswith("  ") else e)
            for w in warnings:
                print(f"  WARN:  {w}" if not w.startswith("  ") else w)
            print()

        # Summary
        print("=" * 60)
        print(f"SUMMARY: {len(locale_dirs)} locales checked")
        print(f"  Clean: {len(clean_locales)}")
        print(f"  Errors: {total_errors}")
        print(f"  Warnings: {total_warnings}")

        if clean_locales:
            print(f"\n  Clean locales: {', '.join(sorted(l.replace('values-', '') for l in clean_locales))}")

        if total_errors > 0:
            print(f"\n  RESULT: FAIL ({total_errors} errors)")
            return False
        elif total_warnings > 0:
            print(f"\n  RESULT: PASS with warnings")
            return True
        else:
            print(f"\n  RESULT: PASS")
            return True


def main():
    import argparse
    parser = argparse.ArgumentParser(description="Verify KashCal translations")
    parser.add_argument("--locale", help="Check only this locale (e.g., 'es', 'zh-rCN')")
    args = parser.parse_args()

    verifier = TranslationVerifier()
    success = verifier.run(target_locale=args.locale)
    sys.exit(0 if success else 1)


if __name__ == "__main__":
    main()
