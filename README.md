# Biodiversity Consensus Ontology Project

This project provides a generalized, configurable pipeline to model dynamic biodiversity consensus using data from [iNaturalist](https://www.inaturalist.org). It fetches observation data, maps taxonomy to NCBI (where possible), builds an OWL 2 DL ontology, and uses SWRL rules to detect epistemic conflicts between agents.

The system includes a self-contained web dashboard powered by a Virtuoso SPARQL endpoint, supporting **multiple iNaturalist projects dynamically**.

## Features

- **Multi-Project Support**: Dynamically add and switch between multiple iNaturalist projects directly from the web interface.
- **Configurable Pipeline**: Run for any iNaturalist project (e.g., `rub-al-khali`, `birds-of-saudi-arabia`).
- **Hybrid Taxonomy**: Integrates the iNaturalist taxonomic backbone with the NCBI Taxonomy OBO, ensuring broad coverage and strict logical grounding.
- **Deep Disjointness**: Automatically generates disjointness axioms based on the iNaturalist tree structure to enable conflict detection across all taxa.
- **Conflict Detection**: Uses HermiT and SWRL to identify observations with incompatible active identifications.
- **Web Dashboard**: A Flask-based interface to visualize statistics, conflicts, and run SPARQL queries.

## Prerequisites

- **Java 11+** (Java 21 recommended)
- **Docker** and **Docker Compose**
- **uv** (Python toolchain)
- **ncbitaxon.obo**: Must be present in the project root or parent directory.
  - Download: `wget https://ftp.ncbi.nlm.nih.gov/pub/taxonomy/maven/ncbitaxon.obo`

## Quick Start (Default: Rub' al Khali)

1.  **Run the Project**:
    ```bash
    ./run_project.sh
    ```
    This script will automatically:
    - Restart Virtuoso (applying configuration).
    - Build the ontology for `rub-al-khali` (fetching data).
    - Load the data into the Knowledge Graph.
    - **Start the Web Dashboard**.

2.  **Explore**: Open [http://localhost:5000](http://localhost:5000) in your browser.
    *(To stop the server, press `Ctrl+C` in the terminal)*

## Adding and Switching Projects Dynamically

From the web interface:

1.  Click the **"+ New Project"** button in the header.
2.  Enter an iNaturalist project slug (e.g., `butterflies-of-oklahoma`).
3.  Click "Build Knowledge Graph".
4.  The system will process the new project in the background (monitor status in the UI).
5.  Once complete, select the project from the dropdown to view its dashboard.

## Architecture

- **ETL**: `project/src/main/groovy/org/example/BiodiversityProject.groovy` (Groovy/OWLAPI)
- **Ontology**: `project/projects_data/<project-slug>.owl` (Generated OWL 2 DL)
- **Database**: Virtuoso (SPARQL Endpoint), storing each project's data in its own named graph.
- **Frontend**: Flask (Python) + HTML/JS, running via `uv`.

## Troubleshooting

- **Virtuoso Access Error**: If you see `FA003: Access to ... denied`, ensure `docker-compose.yml` contains `VIRT_Parameters_DirsAllowed: "., /data"`. The `run_project.sh` script restarts the container to apply this.