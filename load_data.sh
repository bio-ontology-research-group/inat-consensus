#!/bin/bash
# Wait for Virtuoso to start
echo "Waiting for Virtuoso to start..."
until curl -s http://localhost:8890/sparql > /dev/null; do
    sleep 2
    echo "..."
done
echo "Virtuoso is up."

# Load the OWL file
# We assume the file is mapped to /data/rub-al-khali.owl inside the container via docker-compose
echo "Loading ontology..."
docker-compose exec -T virtuoso isql-v 1111 dba dba exec="ld_dir('/data', 'rub-al-khali.owl', 'http://example.org/rub-al-khali');"
docker-compose exec -T virtuoso isql-v 1111 dba dba exec="rdf_loader_run();"
docker-compose exec -T virtuoso isql-v 1111 dba dba exec="checkpoint;"
echo "Data loaded."
