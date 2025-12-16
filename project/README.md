# Rub' al Khali Ontology Project

This project fetches biodiversity observations from the [Rub' al Khali iNaturalist project](https://www.inaturalist.org/projects/rub-al-khali), maps the taxonomy to NCBI, and generates an OWL 2 DL ontology. It uses SWRL rules and the HermiT reasoner to detect "epistemic conflicts"—cases where two active, valid identifications disagree on the species of an observation.

## Prerequisites

- **Java 11+** (Java 21 recommended)
- **Gradle** (or use the provided `gradlew`)
- **ncbitaxon.obo**: You must have the NCBI Taxonomy OBO file in the project root or parent directory. 
  - Download: `wget https://ftp.ncbi.nlm.nih.gov/pub/taxonomy/maven/ncbitaxon.obo`

## Setup

The project is located in the `project/` directory.

```bash
cd project
```

## Running the Project

To fetch data, build the ontology, run the reasoner, and generate the report:

```bash
gradle run
```

*Note: The process involves parsing a large OBO file and running a DL reasoner. It is configured to use up to 8GB of RAM.*

## Output

- **`rub-al-khali.owl`**: The complete ontology file (TBox + ABox).
- **Console Report**: Statistics on observations, identifications, revisions, and disjointness.
- **Verification**: The script runs a self-test by injecting a fake conflicting identification to ensure the reasoning logic is functioning correctly.

## Project Structure

- `src/main/groovy/org/example/RubAlKhaliOntology.groovy`: Main application logic.
- `build.gradle`: Project configuration and dependencies.
