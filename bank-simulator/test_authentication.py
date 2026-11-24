import os
import tempfile
import unittest
from contextlib import ExitStack
from pathlib import Path
from unittest.mock import patch

import db


class AuthenticationTest(unittest.TestCase):
    def setUp(self):
        stack = ExitStack()
        self.addCleanup(stack.close)
        directory = stack.enter_context(tempfile.TemporaryDirectory())
        stack.enter_context(patch.object(db, "DB_PATH", Path(directory) / "test.db"))
        stack.enter_context(patch.dict(os.environ, ATLAS_ADMIN_PASSWORD="atlas-fixture-password"))
        db.init_db()

    def test_identifier_aliases_resolve_the_account_matching_the_password(self):
        db.create_user("client.7", "client-fixture", "CLIENT",
                       customer_id=7, email=" Client@example.test ")
        db.create_user("merchant.7", "merchant-fixture", "MERCHANT",
                       merchant_id=7, email="client@example.test")

        for identifier in ("7", "0007", " CLIENT@example.test "):
            self.assertEqual(db.verify(identifier, "client-fixture")["role"], "CLIENT")
            self.assertEqual(db.verify(identifier, "merchant-fixture")["role"], "MERCHANT")

        self.assertEqual(db.verify(" ADMIN ", "atlas-fixture-password")["role"], "ADMIN")
        self.assertIsNone(db.verify("client.7", "merchant-fixture"))
        for identifier in ("⁷", "9" * 20, "9223372036854775808"):
            self.assertIsNone(db.verify(identifier, "client-fixture"))

    def test_shared_alias_with_identical_password_requires_canonical_username(self):
        db.create_user("client.7", "shared-fixture", "CLIENT", customer_id=7)
        db.create_user("merchant.7", "shared-fixture", "MERCHANT", merchant_id=7)

        self.assertIsNone(db.verify("7", "shared-fixture"))
        self.assertEqual(db.verify("client.7", "shared-fixture")["role"], "CLIENT")
        self.assertEqual(db.verify("merchant.7", "shared-fixture")["role"], "MERCHANT")


if __name__ == "__main__":
    unittest.main()
