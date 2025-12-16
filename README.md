# Biodiversity Consensus Ontology Project

This project provides a generalized, configurable pipeline to model dynamic biodiversity consensus using data from [iNaturalist](https://www.inaturalist.org). It fetches observation data, maps taxonomy to NCBI (where possible), builds an OWL 2 DL ontology, and uses SWRL rules to detect epistemic conflicts between agents.

The system includes a self-contained web dashboard powered by a Virtuoso SPARQL endpoint, now supporting **multiple iNaturalist projects dynamically**.

## Features

- **Multi-Project Support**: Dynamically add and switch between multiple iNaturalist projects directly from the web interface.
- **Configurable Pipeline**: Run for any iNaturalist project (e.g., `rub-al-khali`, `birds-of-saudi-arabia`).
- **Hybrid Taxonomy**: Integrates the iNaturalist taxonomic backbone with the NCBI Taxonomy OBO, ensuring broad coverage and strict logical grounding.
- **Deep Disjointness**: Automatically generates disjointness axioms based on the iNaturalist tree structure to enable conflict detection across all taxa.
- **Conflict Detection**: Uses HermiT and SWRL to identify observations with incompatible active identifications.
- **Web Dashboard**: A Flask-based interface to visualize statistics, conflicts, and run SPARQL queries for selected projects.

## Prerequisites

- **Java 11+** (Java 21 recommended)
- **Docker** and **Docker Compose**
- **Python 3.8+**
- **ncbitaxon.obo**: Must be present in the project root or parent directory.
  - Download: `wget https://ftp.ncbi.nlm.nih.gov/pub/taxonomy/maven/ncbitaxon.obo`

## Quick Start (Default: Rub' al Khali)

1.  **Prepare and Run the Pipeline**:
    ```bash
    ./run_project.sh
    ```
    This script will:
    - Build the ontology for `rub-al-khali` (fetching data from iNaturalist).
    - Start the Virtuoso SPARQL endpoint via Docker.
    - Load the generated RDF data for `rub-al-khali` into a dedicated graph in Virtuoso.

2.  **Start the Website**:
    ```bash
    cd website
    pip install -r requirements.txt
    python app.py
    ```

3.  **Explore**: Open [http://localhost:5000](http://localhost:5000). You should see the dashboard for the "rub-al-khali" project.

## Adding and Switching Projects Dynamically

From the web interface ([http://localhost:5000](http://localhost:5000)):

1.  Click the **"+ New Project"** button in the header.
2.  Enter an iNaturalist project slug (e.g., `butterflies-of-oklahoma`, `birds-of-saudi-arabia`).
3.  Click "Build Knowledge Graph".
4.  The system will process the new project in the background. You can monitor its status via the status bar in the UI.
5.  Once processing is complete, the new project will appear in the dropdown selector. Select it to view its specific dashboard.

## Architecture

- **ETL**: `project/src/main/groovy/org/example/BiodiversityProject.groovy` (Groovy/OWLAPI)
- **Ontology**: `project/projects_data/<project-slug>.owl` (Generated OWL 2 DL)
- **Database**: Virtuoso (SPARQL Endpoint), storing each project's data in its own named graph.
- **Frontend**: Flask (Python) + HTML/JS, dynamically interacting with the Virtuoso endpoint.

## Project Structure

- `project/`: Groovy source code and Gradle build.
- `project/projects_data/`: Directory where generated OWL files are stored.
- `website/`: Flask application code.
- `run_project.sh`: Automation script to prepare a project from the command line (primarily for initial setup or specific project rebuilds).
- `docker-compose.yml`: Virtuoso container configuration.