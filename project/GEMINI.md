# Gemini Agentic Coding Instructions

This project is a Groovy-based semantic web application that models biodiversity data from iNaturalist using OWL 2 DL and SIO.

## Project Structure
- **Language**: Groovy 3.x
- **Build Tool**: Gradle 8.x
- **Key Libraries**: 
  - OWLAPI 4.5.26 (Note: Do not upgrade to 5.x due to HermiT compatibility)
  - HermiT 1.4.3.456 (Reasoner)
  - SLF4J (Logging)

## Core Logic (`RubAlKhaliOntology.groovy`)
1.  **Data Fetching**: Pulls JSON data from iNaturalist API.
2.  **Taxonomy Mapping**: Maps text names to NCBI IDs using `ncbitaxon.obo`.
    - *Constraint*: The OBO file is large (500MB+). Do not read it all into memory as objects. Use streaming/line-by-line parsing.
3.  **Ontology Generation**:
    - **TBox**: Defines `IdentificationAct`, `ActiveIdentification`, etc.
    - **ABox**: Instantiates Observations and Identification Acts.
    - **Disjointness**: Generates `isIncompatibleWith` properties for *all* disjoint taxon pairs (Deep Disjointness) to support SWRL.
4.  **Reasoning**:
    - Uses SWRL rules to detect `ConflictingObservation`.
    - Rule: `ActiveId(a1) ^ ActiveId(a2) ^ target(a1, o) ^ target(a2, o) ^ output(a1, t1) ^ output(a2, t2) ^ incompatible(t1, t2) -> Conflicting(o)`

## Development Workflow
- **Run**: `cd project && gradle run`
- **Modify**: Edit `src/main/groovy/org/example/RubAlKhaliOntology.groovy`.
- **Test**: The `run` task includes a built-in verification step (`verifyConflictDetection`) that artificially injects a conflict to prove the logic works.

## Conventions
- Use `EX_NS` (http://example.org/rub-al-khali/) for project-specific terms.
- Use `SIO_NS` (http://semanticscience.org/resource/) for core ontology.
- Ensure `java.base/java.lang=ALL-UNNAMED` is set in JVM args for OWLAPI reflection.
