#!/usr/bin/env python3
"""Offline checks for fireworks_prices.py: the Standard column is read as input/output, non-chat models are not listed."""
import unittest
import fireworks_prices as fp

ROW = ('_jsx(_components.a,{href:`https://app.fireworks.ai/models/fireworks/ember-1`,children:`Ember-1`})}),'
       '_jsx(_components.td,{\\"data-numeric\\":`true`,children:`$3.00 / $0.30 / $15.00`}),'
       '_jsx(_components.td,{\\"data-numeric\\":`true`,children:`$3.75 / $0.375 / $18.75`})')


class FireworksPricesTest(unittest.TestCase):
    def test_standard_column_is_input_and_output_not_cached(self):
        self.assertEqual({"ember-1": "$3.00/$15.00"}, fp.page_prices(ROW))

    def test_embeddings_and_rerankers_are_not_chat(self):
        recs = [{"name": "accounts/fireworks/models/qwen3-embedding-8b"}, {"name": "accounts/fireworks/models/qwen3-reranker-8b"},
                {"name": "accounts/fireworks/models/ember-1", "state": "READY"}, {"name": "accounts/fireworks/models/old", "deprecationDate": "2026-01-01"}]
        self.assertEqual(["ember-1"], fp.live_chat_models(recs))

    def test_the_shipped_table_parses(self):
        self.assertIn("kimi-k3", fp.shipped())


if __name__ == "__main__":
    unittest.main()
