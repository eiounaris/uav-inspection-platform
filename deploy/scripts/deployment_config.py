"""Explicit infrastructure project selection for normal and isolated deployments.

Read this setting only from the literal configuration passed by the caller.
An ambient COMPOSE_PROJECT_NAME must never redirect a persistence probe.
"""
import re


DEFAULT_INFRA_PROJECT = "uav-inspection"
PROJECT_PATTERN = re.compile(r"[a-z0-9][a-z0-9_-]{0,62}")


def infrastructure_project(config):
    value = config.get("INFRA_COMPOSE_PROJECT_NAME") or DEFAULT_INFRA_PROJECT
    if not isinstance(value, str) or not PROJECT_PATTERN.fullmatch(value):
        raise ValueError("Invalid INFRA_COMPOSE_PROJECT_NAME; use 1-63 lowercase letters, digits, underscores or hyphens")
    return value


def bind_probe_project(state, project):
    """Bind a loaded active state; legacy states belong only to the old default."""
    previous = state.get("project_name", DEFAULT_INFRA_PROJECT)
    if previous != project:
        raise ValueError("Probe state belongs to another infrastructure project; use its original configuration or a new state file")
    state["project_name"] = project
    return state
