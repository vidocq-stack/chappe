"""Checks the alerts of the nightly ZAP scan against the accepted ones.

Usage: check.py <zap.json> <accepted.tsv>

Prints every alert ZAP raised (risk, name, plugin id, URLs) and whether it is accepted; exits 1 when an
alert of risk Low or above is not in accepted.tsv, so that the nightly turns red on a new finding only.
Informational alerts are printed and never fail.
"""
import json
import sys

report_path, accepted_path = sys.argv[1:3]
accepted = {}
with open(accepted_path, encoding="utf-8") as fh:
    for line in fh:
        line = line.rstrip("\n")
        if line and not line.startswith("#"):
            plugin, _, reason = line.partition("\t")
            accepted[plugin.strip()] = reason.strip()

with open(report_path, encoding="utf-8") as fh:
    report = json.load(fh)

new = 0
alerts = [a for site in report.get("site", []) for a in site.get("alerts", [])]
for alert in sorted(alerts, key=lambda a: -int(a.get("riskcode", 0))):
    plugin, risk = str(alert.get("pluginid")), int(alert.get("riskcode", 0))
    uris = sorted({i.get("uri", "") for i in alert.get("instances", [])})
    if plugin in accepted:
        verdict = "accepted: " + accepted[plugin]
    elif risk == 0:
        verdict = "informational"
    else:
        verdict = "NEW"
        new += 1
    print("%-14s %s (plugin %s, %d URL%s, e.g. %s): %s" % (
        alert.get("riskdesc", "?"), alert.get("alert", "?"), plugin, len(uris), "" if len(uris) == 1 else "s",
        uris[0] if uris else "-", verdict))

print("ZAP: %d alert(s), %d new" % (len(alerts), new))
sys.exit(1 if new else 0)
