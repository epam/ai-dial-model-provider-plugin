#!/usr/bin/env python3
"""Check the credentials the plugin module needs to resolve DIAL Core's plugin API from GitHub Packages.

GPR_USERNAME and GPR_PASSWORD must be set and accepted by GitHub. A classic token reports its scopes, and must have
read:packages (write:packages includes it). A GITHUB_TOKEN or a fine-grained token reports none: then the registry itself
judges, with a request for the metadata of the pinned plugin API version. A GitHub that cannot be reached is a warning.
The token goes only into request headers, never into an output.
"""

import os
import sys
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
REGISTRY = "https://maven.pkg.github.com/epam/ai-dial-core/com/epam/aidial/aidial-core-plugin-api"
PACKAGE_SCOPES = {"read:packages", "write:packages"}


def fail(message: str) -> None:
    sys.exit(message)


def plugin_api_version() -> str:
    for line in (ROOT / "gradle.properties").read_text().splitlines():
        if line.startswith("plugin_api_version="):
            return line.split("=", 1)[1].strip()
    fail("plugin_api_version is not set in gradle.properties")


def get(url: str, token: str):
    """The response (or the HTTPError, which has the same status and headers); None when GitHub cannot be reached."""
    request = urllib.request.Request(url, headers={"Authorization": f"Bearer {token}"})
    try:
        return urllib.request.urlopen(request, timeout=20)
    except urllib.error.HTTPError as error:
        return error
    except OSError:
        return None


def main() -> None:
    username, token = os.environ.get("GPR_USERNAME"), os.environ.get("GPR_PASSWORD")
    if not username or not token:
        fail(
            "GPR_USERNAME and GPR_PASSWORD are not set: the plugin module resolves the DIAL Core plugin API from "
            "GitHub Packages.\nSet them to your GitHub user and a token with the read:packages scope "
            '(README "Building the plugin jar").'
        )

    user = get("https://api.github.com/user", token)
    if user is None:
        print("warning: GitHub cannot be reached, GPR_PASSWORD is not checked", file=sys.stderr)
        return
    if user.status == 401:
        fail("GPR_PASSWORD is rejected by GitHub (401): the token is invalid or expired.")

    scopes = user.headers.get("X-OAuth-Scopes")
    if scopes is not None:
        granted = {scope.strip() for scope in scopes.split(",") if scope.strip()}
        if not granted & PACKAGE_SCOPES:
            fail(
                f"GPR_PASSWORD has the scopes '{','.join(sorted(granted))}' and lacks read:packages: "
                "add the scope at https://github.com/settings/tokens"
            )
        return

    version = plugin_api_version()
    registry = get(f"{REGISTRY}/{version}/maven-metadata.xml", token)
    if registry is not None and registry.status not in (200, 404):
        fail(
            f"GitHub Packages answers {registry.status} for aidial-core-plugin-api {version}: "
            "GPR_PASSWORD needs the read:packages scope"
        )


if __name__ == "__main__":
    main()
