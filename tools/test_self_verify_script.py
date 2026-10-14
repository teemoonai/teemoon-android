#!/usr/bin/env python3
# ports: tools/test_self_verify_script.py @ c9498d5 (iOS), unchanged but for this line — it runs the KOTLIN generator's script
#        through tools/render_self_verify.py:   python3 tools/test_self_verify_script.py
"""The self-verify script, RUN — not read.

Its Swift tests assert on the generated TEXT (58 `script.contains(...)` at the
time of writing) and nothing anywhere executed it. So when near.ai's gateway
began requiring authentication, every run reported the gateway half as failed
— and, worse, read the resulting ABSENCE of evidence as CHANGED evidence:
"code identity AND signing key both changed since your session — unexpected;
treat with suspicion", printed at a service that had done nothing. A trust
tool that cries wolf costs more than one with a gap, and 58 green assertions
had nothing to say about it (both fixed 2026-08-22).

These run the rendered script against fixture responses, offline, and assert
the tallies. The false-alarm cases are the point.
"""

import contextlib
import hashlib
import io
import json
import sys
import types
import unittest
import urllib.error

from render_self_verify import render

#: The fixture's compose documents, and the hashes the enclaves "attest" to
#: them. Section 3 re-hashes each manifest and compares, so these pairs have to
#: be REAL — and the session below has to carry the same hashes, or section 2
#: reports a change the fixture never made. The test owns both sides.
GATEWAY_MANIFEST = ("services:\n  cloud-api:\n"
                    "    image: nearaidev/cloud-api@sha256:" + "ab" * 32 + "\n")
MODEL_MANIFEST = ("services:\n  proxy:\n"
                  "    image: nearaidev/vllm-proxy-rs@sha256:" + "cd" * 32 + "\n"
                  "  nginx:\n"
                  "    image: nginx@sha256:" + "ee" * 32 + "\n")
GATEWAY_SHA = hashlib.sha256(GATEWAY_MANIFEST.encode()).hexdigest()
MODEL_SHA = hashlib.sha256(MODEL_MANIFEST.encode()).hexdigest()

GATEWAY_SIGNER = "0xaaaa000000000000000000000000000000000001"
MODEL_SIGNER = "0xbbbb000000000000000000000000000000000002"
MRTD = "d0" + "0" * 94
OS_IMAGE = "os" + "0" * 62
ED25519 = "ed" + "0" * 62

SESSION = f'''SESSION = {{
    "captured_at":             "2026-01-01T00:00:00Z",
    "model":                   "test/model-1",
    "gateway_base":            "https://gateway.example/v1",
    "direct_base":             "https://model.example/v1",
    "gateway_signing_address": "{GATEWAY_SIGNER}",
    "gpu_signing_address":     "{MODEL_SIGNER}",
    "compose_hash":            "{GATEWAY_SHA}",
    "gpu_node_compose_hash":   "{MODEL_SHA}",
    "model_ed25519_pubkey":    "{ED25519}",
    "mrtd":                    "{MRTD}",
    "os_image_hash":           "{OS_IMAGE}",
}}
'''


def gateway_report(*, signing=GATEWAY_SIGNER, compose=None, mrtd=MRTD,
                   os_image=OS_IMAGE, manifest=None):
    """The gateway's report, matching the session unless a test says otherwise."""
    return {
        "signing_address": signing,
        "info": {
            "app_compose": manifest if manifest is not None else GATEWAY_MANIFEST,
            "compose_hash": compose if compose is not None else GATEWAY_SHA,
            "tcb_info": {"mrtd": mrtd},
            "os_image_hash": os_image,
            "model_name": "test/model-1",
        },
    }


def model_report(*, signing=MODEL_SIGNER, compose=None, manifest=None):
    """The model host's report."""
    return {
        "signing_address": signing,
        "info": {
            "app_compose": manifest if manifest is not None else MODEL_MANIFEST,
            "compose_hash": compose if compose is not None else MODEL_SHA,
            "model_name": "test/model-1",
        },
    }


class Service:
    """Stands in for the network. Every fetch the script makes lands here."""

    def __init__(self, gateway=None, model_host=None, gateway_status=None,
                 ed25519=ED25519):
        self.gateway = gateway
        self.model_host = model_host
        self.gateway_status = gateway_status
        #: The E2EE key the session encrypted to, echoed on an ed25519 fetch.
        self.ed25519 = ed25519
        self.authorized = None

    def urlopen(self, req, timeout=None):
        url = req.full_url if hasattr(req, "full_url") else str(req)
        if "gateway.example" in url:
            self.authorized = bool(req.headers.get("Authorization"))
            if self.gateway_status:
                raise urllib.error.HTTPError(url, self.gateway_status, "nope", {}, None)
            payload = self.gateway
        elif "model.example" in url:
            payload = self.model_host
        else:
            # GitHub provenance / raw YAML: unavailable, which the script must
            # report as "no evidence either way" rather than as a failure.
            raise urllib.error.HTTPError(url, 503, "unavailable", {}, None)
        if payload is None:
            raise urllib.error.HTTPError(url, 404, "absent", {}, None)
        # The nonce echo: the script checks its nonce appears in the response.
        nonce = url.split("nonce=")[1].split("&")[0]
        echoed = {**payload, "nonce": nonce}
        # An ed25519 fetch is how the script finds the model's E2EE key.
        if "signing_algo=ed25519" in url:
            echoed["signing_address_ed25519"] = self.ed25519
        return contextlib.closing(io.BytesIO(json.dumps(echoed).encode()))


def run(service, *, api_key=None):
    """Execute the rendered script's `main()` against `service`.

    `urlopen` is patched on the module the script imported, and restored: the
    script holds `urllib` itself, so a leaked patch would follow the process
    into the next test.
    """
    namespace = {"__name__": "selfverify_under_test"}
    exec(compile(render(SESSION), "teemoon_verify.py", "exec"), namespace)
    namespace["API_KEY"] = api_key or ""
    urllib_module = namespace["urllib"]
    original = urllib_module.request.urlopen
    urllib_module.request.urlopen = service.urlopen
    # Section 5 opens a REAL socket to read the live TLS key off the wire,
    # which is the one check that cannot be faked into a fixture without also
    # faking a certificate. Denying it `cryptography` sends it down its own
    # documented skip path, which keeps this suite hermetic and exercises that
    # path. The binding itself is covered by a keyed manual run (verified
    # 2026-08-22: "live TLS key (same connection) matches the attested
    # fingerprint") and by `--full`.
    denied = types.ModuleType("cryptography")
    denied.__getattr__ = lambda name: (_ for _ in ()).throw(ImportError(name))
    saved_crypto = sys.modules.get("cryptography")
    sys.modules["cryptography"] = denied
    try:
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            namespace["main"]()
        return namespace["counts"], out.getvalue()
    finally:
        urllib_module.request.urlopen = original
        if saved_crypto is None:
            sys.modules.pop("cryptography", None)
        else:
            sys.modules["cryptography"] = saved_crypto


class SelfVerifyBehaviour(unittest.TestCase):

    def test_an_unchanged_service_passes_and_checks_the_measurements(self):
        service = Service(gateway=gateway_report(), model_host=model_report())
        counts, text = run(service, api_key="key")
        self.assertEqual(counts["failed"], 0, text)
        self.assertIn("gateway signing key is the one your session verified", text)
        # The measurements it PRINTS are measurements it CHECKS.
        self.assertIn("gateway TDX measurement (MRTD) is the one your session", text)
        self.assertIn("gateway guest OS image is the one your session", text)

    def test_no_key_skips_the_gateway_instead_of_crying_wolf(self):
        service = Service(gateway_status=401, model_host=model_report())
        counts, text = run(service)
        self.assertEqual(counts["failed"], 0, text)
        self.assertIn("needs your API key", text)
        self.assertIn("not checked", text)
        # The regression this file exists for.
        self.assertNotIn("treat with suspicion", text)
        self.assertNotIn("changed since your session", text)

    def test_no_key_summary_does_not_call_an_unchecked_gateway_checked_out(self):
        # 2026-09-01: a keyless --full run printed "13 passed · 0 failed ·
        # 12 skipped — everything checked out" with the whole gateway (the path
        # teemoon encrypts through) unchecked. Skips a remedy could turn into
        # real checks must show up in the verdict.
        service = Service(gateway_status=401, model_host=model_report())
        counts, text = run(service)
        self.assertNotIn("everything checked out", text)
        self.assertIn("nothing failed", text)
        # gateway report + signing key + compose hash + MRTD + OS image
        self.assertGreaterEqual(counts["unchecked"], 5, text)
        summary = text.split("summary", 1)[1]
        self.assertIn("NEARAI_CLOUD_API_KEY", summary)

    def test_by_design_skips_do_not_count_as_unchecked(self):
        # With a key the gateway is checked. What remains unchecked is exactly
        # what a remedy could still run: the harness denies cryptography (1),
        # --full is not passed (1), and the fixture's GitHub answers 503 for
        # both near.ai images (2). The third-party nginx skip and the fixture's
        # missing compose_up action have no remedy and must not be counted.
        service = Service(gateway=gateway_report(), model_host=model_report())
        counts, text = run(service, api_key="key")
        self.assertEqual(counts["failed"], 0, text)
        self.assertIn("nginx@eeeeeeeeeeee…: third-party base image", text)
        self.assertEqual(counts["unchecked"], 4, text)
        self.assertEqual(counts["skipped"], 6, text)
        self.assertNotIn("NEARAI_CLOUD_API_KEY", text.split("summary", 1)[1])

    def test_the_gateway_request_carries_the_key_when_there_is_one(self):
        service = Service(gateway=gateway_report(), model_host=model_report())
        run(service, api_key="key")
        self.assertTrue(service.authorized, "the gateway fetch went out unauthenticated")

    def test_a_401_with_a_key_is_a_real_failure(self):
        service = Service(gateway_status=401, model_host=model_report())
        counts, text = run(service, api_key="key")
        self.assertGreater(counts["failed"], 0, text)

    def test_a_changed_signing_key_still_fails_when_evidence_is_present(self):
        service = Service(gateway=gateway_report(signing="0xdead" + "0" * 35),
                          model_host=model_report())
        counts, text = run(service, api_key="key")
        self.assertGreater(counts["failed"], 0, text)
        self.assertIn("signing key changed since your session", text)

    def test_a_redeploy_with_intact_keys_warns_rather_than_fails(self):
        # New compose, new machine measurements, same sealed signing key: the
        # signature of a legitimate redeploy, not of tampering.
        redeployed = GATEWAY_MANIFEST.replace("cloud-api@sha256:" + "ab" * 32,
                                              "cloud-api@sha256:" + "ef" * 32)
        service = Service(
            gateway=gateway_report(manifest=redeployed,
                                   compose=hashlib.sha256(redeployed.encode()).hexdigest(),
                                   mrtd="ff" + "0" * 94, os_image="ff" + "0" * 62),
            model_host=model_report())
        counts, text = run(service, api_key="key")
        self.assertEqual(counts["failed"], 0, text)
        self.assertGreater(counts["warned"], 0, text)
        self.assertIn("legitimate redeploy", text)


class SelfVerifyContract(unittest.TestCase):

    def test_every_session_field_is_actually_checked(self):
        """A value shown under SESSION is a value the reader is told was
        verified. `mrtd` and `os_image_hash` were embedded, printed and never
        compared against anything until 2026-08-23."""
        script = render()
        head, rest = script.split("SESSION = {", 1)
        block, body = rest.split("\n}\n", 1)
        fields = [line.split('"')[1] for line in block.splitlines()
                  if line.strip().startswith('"')]
        self.assertGreater(len(fields), 5)
        # "Read" means the name appears in the body at all — the checks index
        # SESSION directly in some places and loop over field names in others
        # (`for field, what in (("mrtd", …), …)`), and a contract test that
        # insisted on one spelling would have passed the very bug it is here
        # to catch.
        unused = [f for f in fields
                  if f'"{f}"' not in body and f"'{f}'" not in body]
        self.assertEqual(unused, [],
                         f"embedded under SESSION but never read: {unused}")


if __name__ == "__main__":
    unittest.main()
