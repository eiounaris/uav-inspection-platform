"""Validated host-side Fabric deployment settings; internal DNS/ports stay fixed."""

from dataclasses import dataclass
import os
from pathlib import Path
import re


ROOT = Path(__file__).resolve().parents[2]
DEFAULTS = {
    "FABRIC_COMPOSE_PROJECT_NAME": "uav-inspection-fabric",
    "FABRIC_ORDERER_PORT": "7050",
    "FABRIC_ORDERER_ADMIN_PORT": "7053",
    "FABRIC_ORGA_PEER_PORT": "7051",
    "FABRIC_ORGB_PEER_PORT": "9051",
    "FABRIC_CHAINCODE_IMAGE": "uav-inspection/evidence-chaincode:0.1.0",
}


def literal_environment(path):
    """Parse data only; do not source .env or expose arbitrary values in errors."""
    values = {}
    if not path.is_file():
        return values
    for number, raw in enumerate(path.read_text().splitlines(), 1):
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        key, separator, value = line.partition("=")
        if not separator or not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", key) or key in values:
            raise RuntimeError("Invalid local .env entry at line " + str(number))
        if value.startswith(("'", '"')):
            if len(value) < 2 or value[-1] != value[0]:
                raise RuntimeError("Invalid quoted local .env entry at line " + str(number))
            value = value[1:-1]
        else:
            value = re.split(r"\s+#", value, maxsplit=1)[0].rstrip()
        values[key] = value
    return values


@dataclass(frozen=True)
class DeploymentSettings:
    project_name: str
    orderer_port: int
    orderer_admin_port: int
    orga_peer_port: int
    orgb_peer_port: int
    chaincode_image: str

    def peer_endpoint(self, org):
        if org not in (1, 2):
            raise RuntimeError("Expected Fabric organization 1 or 2")
        return "localhost:" + str(self.orga_peer_port if org == 1 else self.orgb_peer_port)

    @property
    def orderer_endpoint(self):
        return "localhost:" + str(self.orderer_port)

    @property
    def orderer_admin_endpoint(self):
        return "localhost:" + str(self.orderer_admin_port)

    def compose_environment(self):
        return {"FABRIC_COMPOSE_PROJECT_NAME": self.project_name,
                "FABRIC_ORDERER_PORT": str(self.orderer_port),
                "FABRIC_ORDERER_ADMIN_PORT": str(self.orderer_admin_port),
                "FABRIC_ORGA_PEER_PORT": str(self.orga_peer_port),
                "FABRIC_ORGB_PEER_PORT": str(self.orgb_peer_port),
                "FABRIC_CHAINCODE_IMAGE": self.chaincode_image}

    def namespace(self):
        return {"formatVersion": 1, "composeProjectName": self.project_name,
                "hostPorts": {"orderer": self.orderer_port, "ordererAdmin": self.orderer_admin_port,
                              "orgA": self.orga_peer_port, "orgB": self.orgb_peer_port},
                "chaincodeImage": self.chaincode_image}


def load_settings(root=ROOT, environ=None):
    environment = os.environ if environ is None else environ
    file_values = literal_environment(Path(root) / ".env")
    values = {key: environment.get(key, file_values.get(key, default)) for key, default in DEFAULTS.items()}
    project, image = values["FABRIC_COMPOSE_PROJECT_NAME"], values["FABRIC_CHAINCODE_IMAGE"]
    if not isinstance(project, str) or not re.fullmatch(r"[a-z0-9][a-z0-9_-]{0,62}", project):
        raise RuntimeError("Invalid FABRIC_COMPOSE_PROJECT_NAME")
    # Conservative Docker reference syntax: no whitespace, expansion or implicit latest.
    image_pattern = (r"(?:[a-z0-9]+(?:[._-][a-z0-9]+)*(?::[0-9]{1,5})?/)*"
                     r"[a-z0-9]+(?:[._-][a-z0-9]+)*"
                     r"(?::[A-Za-z0-9_][A-Za-z0-9_.-]{0,127})?(?:@sha256:[0-9a-f]{64})?")
    if (not isinstance(image, str) or len(image) > 255 or not re.fullmatch(image_pattern, image)
            or ":" not in image.rsplit("/", 1)[-1] or image.endswith(":latest")):
        raise RuntimeError("Invalid fixed FABRIC_CHAINCODE_IMAGE reference")
    ports = []
    for key in ("FABRIC_ORDERER_PORT", "FABRIC_ORDERER_ADMIN_PORT", "FABRIC_ORGA_PEER_PORT", "FABRIC_ORGB_PEER_PORT"):
        value = values[key]
        if not isinstance(value, str) or not value.isascii() or not value.isdecimal() or not 1 <= int(value) <= 65535:
            raise RuntimeError("Invalid " + key)
        ports.append(int(value))
    if len(set(ports)) != len(ports):
        raise RuntimeError("Fabric host ports must be distinct")
    return DeploymentSettings(project, *ports, image)


DEFAULT_SETTINGS = DeploymentSettings("uav-inspection-fabric", 7050, 7053, 7051, 9051,
                                      "uav-inspection/evidence-chaincode:0.1.0")
SETTINGS = load_settings()


def check_namespace(materials, settings=SETTINGS):
    namespace = materials.get("deployment_namespace")
    if namespace is None:
        if settings != DEFAULT_SETTINGS:
            raise RuntimeError("Existing materials have no deployment namespace; only the original default deployment is allowed")
    elif namespace != settings.namespace():
        raise RuntimeError("Fabric deployment namespace differs from existing materials; restore the original project, ports and image")
