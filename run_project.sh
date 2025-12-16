#!/bin/bash

# Default to rub-al-khali
PROJECT=${1:-rub-al-khali}

echo "=========================================="
echo "RUNNING BIODIVERSITY CONSENSUS PIPELINE"
echo "Initializing Default Project: $PROJECT"
echo "=========================================="

# 1. Reset Virtuoso
echo "[1/4] Starting Virtuoso..."
docker-compose down
docker-compose up -d

# Wait for Virtuoso
echo "Waiting for Virtuoso..."
until curl -s http://localhost:8890/sparql > /dev/null; do
    sleep 2
    echo "."
done

# 2. Build Ontology (run gradle with project dir context)
echo "[2/4] Building Ontology..."
cd project
mkdir -p projects_data
# We use the new BiodiversityProject which outputs to projects_data/<slug>.owl
gradle run --args="$PROJECT"
cd ..

# 3. Load Data
echo "[3/4] Loading Data into SPARQL Endpoint..."
# Load into named graph: http://example.org/biodiversity/<slug>
GRAPH_URI="http://example.org/biodiversity/$PROJECT"
OWL_FILE="$PROJECT.owl"

docker-compose exec -T virtuoso isql-v 1111 dba dba exec="ld_dir('/data/projects_data', '$OWL_FILE', '$GRAPH_URI');"
docker-compose exec -T virtuoso isql-v 1111 dba dba exec="rdf_loader_run();"
docker-compose exec -T virtuoso isql-v 1111 dba dba exec="checkpoint;"

echo "[4/4] Done!"
echo "SPARQL Endpoint: http://localhost:8890/sparql"
echo "Website: http://localhost:5000"
echo ""
echo "To start the dynamic website:"
echo "cd website && python app.py"