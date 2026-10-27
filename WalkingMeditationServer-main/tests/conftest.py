import pytest


@pytest.fixture(autouse=True)
def forbid_real_aws_requests(monkeypatch):
    """Unit tests must mock/stub AWS; a forgotten mock must never incur usage."""
    def forbidden(*args, **kwargs):
        raise AssertionError("Real AWS HTTP calls are disabled in unit tests")
    monkeypatch.setattr("botocore.httpsession.URLLib3Session.send", forbidden)

@pytest.fixture(autouse=True)
def default_script_fallback(monkeypatch):
    monkeypatch.setenv("SCRIPT_FALLBACK_ENABLED", "true")