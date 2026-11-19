Simulated smart-farm dataset used by Agro-CTI (seed 2026): 24 nodes in 4 districts, 40 days at 15-minute
steps (21 training, 5 validation, 14 test days), 92,160 readings, attacks only in the test days.
  telemetry.csv        8 sensor channels per node and step, ground-truth label, context flags
  actuator_log.csv     every valve command with its source IP address
  attack_episodes.csv  start, end, node and type of every attack
  threat_feed.json     external threat-intelligence feed (IPv4 indicators, regional advisories)
The program regenerates exactly the same files with:  ./run.sh generate --seed 2026
