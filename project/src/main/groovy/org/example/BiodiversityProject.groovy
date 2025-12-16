package org.example

import groovy.json.JsonSlurper
import org.semanticweb.owlapi.apibinding.OWLManager
import org.semanticweb.owlapi.model.*
import org.semanticweb.owlapi.reasoner.*
import org.semanticweb.owlapi.util.DefaultPrefixManager
import org.semanticweb.HermiT.ReasonerFactory

class BiodiversityProject {
    static final String INAT_API_BASE = "https://api.inaturalist.org/v1"
    
    // Configurable via args
    String projectSlug = "rub-al-khali"
    
    // Namespaces
    static final String SIO_NS = "http://semanticscience.org/resource/"
    static final String EX_NS = "http://example.org/biodiversity/"
    static final String OBO_NS = "http://purl.obolibrary.org/obo/"
    
    // Mappings
    Map<String, String> nameToNcbiId = [:]
    Map<Integer, Integer> ncbiParentMap = [:]
    Map<Integer, String> iNatIdToName = [:]
    Map<Integer, Integer> iNatParentMap = [:] 
    Set<Integer> allINatIds = new HashSet<>()
    Map<Integer, IRI> finalClassMap = [:]

    int mappedTaxaCount = 0
    int unmappedTaxaCount = 0
    
    OWLDataFactory dataFactory

    public static void main(String[] args) {
        String slug = "rub-al-khali"
        if (args.length > 0) {
            slug = args[0]
        }
        new BiodiversityProject(projectSlug: slug).run()
    }

    void run() {
        println "Starting Biodiversity Consensus Project for: ${projectSlug}"
        
        // 1. Fetch Data
        def observations = fetchObservations()
        println "Fetched ${observations.size()} observations."

        // 2. Process Taxonomy
        processTaxonomy(observations)
        
        // 3. Build Ontology
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager()
        dataFactory = manager.getOWLDataFactory()
        
        OWLOntology ontology = buildOntology(manager, observations)
        
        // 4. Save Ontology (Specific to project)
        File dataDir = new File("projects_data")
        if (!dataDir.exists()) dataDir.mkdirs()
        
        File callbackFile = new File(dataDir, "${projectSlug}.owl")
        manager.saveOntology(ontology, IRI.create(callbackFile.toURI()))
        println "Ontology saved to ${callbackFile.absolutePath}"

        // 5. Reasoning
        runReasoner(ontology)

        // 6. Report
        generateReport(ontology, observations)
    }

    List<Map> fetchObservations() {
        List<Map> allObs = []
        int page = 1
        int perPage = 200
        boolean more = true
        
        while (more) {
            String url = "${INAT_API_BASE}/observations?project_id=${projectSlug}&per_page=${perPage}&page=${page}&order_by=created_at&order=desc"
            println "Fetching page ${page}..."
            try {
                def json = new URL(url).getText(requestProperties: ['User-Agent': 'BiodiversityBot/1.0'])
                def result = new JsonSlurper().parseText(json)
                def results = result.results
                
                if (!results) {
                    more = false
                } else {
                    allObs.addAll(results)
                    page++
                    if (page > 50) more = false 
                }
                sleep(1000)
            } catch (Exception e) {
                println "Error fetching page ${page}: ${e.message}"
                more = false
            }
        }
        return allObs
    }

    void processTaxonomy(List<Map> observations) {
        println "Processing taxonomy..."
        
        Closure processTaxonObj = { Map taxon ->
            if (!taxon) return
            Integer tid = taxon.id as Integer
            if (taxon.name) iNatIdToName[tid] = taxon.name.toLowerCase()
            allINatIds.add(tid)
            
            if (taxon.ancestor_ids) {
                List<Integer> ancestors = taxon.ancestor_ids.collect { it as Integer }
                for (int i = 0; i < ancestors.size() - 1; i++) {
                    iNatParentMap[ancestors[i+1]] = ancestors[i]
                    allINatIds.add(ancestors[i])
                    allINatIds.add(ancestors[i+1])
                }
                if (!ancestors.isEmpty() && ancestors.last() != tid) {
                     iNatParentMap[tid] = ancestors.last()
                }
            }
        }
        
        observations.each { obs ->
            processTaxonObj(obs.taxon)
            obs.identifications.each { ident -> processTaxonObj(ident.taxon) }
        }
        println "Found ${allINatIds.size()} unique taxa in dataset."

        // Parse OBO
        File oboFile = new File("../ncbitaxon.obo")
        if (!oboFile.exists()) oboFile = new File("ncbitaxon.obo")
        if (oboFile.exists()) {
            println "Parsing ncbitaxon.obo..."
            BufferedReader reader = new BufferedReader(new FileReader(oboFile))
            String line
            Integer currentId = null
            String currentName = null
            Integer parentId = null
            while ((line = reader.readLine()) != null) {
                line = line.trim()
                if (line == "[Term]") {
                    if (currentId != null) {
                        if (parentId != null) ncbiParentMap[currentId] = parentId
                        if (currentName != null) nameToNcbiId[currentName] = "NCBITaxon:" + currentId
                    }
                    currentId = null; currentName = null; parentId = null
                } else if (line.startsWith("id: NCBITaxon:")) {
                    try { currentId = Integer.parseInt(line.substring(14).trim()) } catch (Exception e) {}
                } else if (line.startsWith("name:")) {
                    currentName = line.substring(5).trim().toLowerCase()
                } else if (line.startsWith("is_a: NCBITaxon:")) {
                    if (parentId == null) {
                         try { parentId = Integer.parseInt(line.substring(16).trim().split("!")[0].trim()) } catch (Exception e) {}
                    }
                }
            }
            reader.close()
        } else {
            println "Warning: ncbitaxon.obo not found."
        }
        
        // Resolve
        allINatIds.each { id ->
            String name = iNatIdToName[id]
            if (name && nameToNcbiId.containsKey(name)) {
                String ncbiStr = nameToNcbiId[name]
                String justId = ncbiStr.split(":")[1]
                finalClassMap[id] = IRI.create(OBO_NS + "NCBITaxon_" + justId)
                mappedTaxaCount++
            } else {
                finalClassMap[id] = IRI.create(EX_NS + "iNatTaxon_" + id)
                if (name) unmappedTaxaCount++
            }
        }
        println "Mapping complete. Mapped: ${mappedTaxaCount}, Unmapped: ${unmappedTaxaCount}"
    }

    OWLOntology buildOntology(OWLOntologyManager manager, List<Map> observations) {
        OWLOntology ontology = manager.createOntology(IRI.create(EX_NS))
        
        // Vocab
        OWLClass cObservation = dataFactory.getOWLClass(IRI.create(SIO_NS + "Observation"))
        OWLClass cProcess = dataFactory.getOWLClass(IRI.create(SIO_NS + "process"))
        OWLClass cAgent = dataFactory.getOWLClass(IRI.create(SIO_NS + "Agent"))
        OWLClass cTaxon = dataFactory.getOWLClass(IRI.create(SIO_NS + "Taxon"))
        OWLClass cIdAct = dataFactory.getOWLClass(IRI.create(EX_NS + "IdentificationAct"))
        OWLClass cActiveId = dataFactory.getOWLClass(IRI.create(EX_NS + "ActiveIdentification"))
        OWLClass cConflicting = dataFactory.getOWLClass(IRI.create(EX_NS + "ConflictingObservation"))
        
        OWLObjectProperty opHasAgent = dataFactory.getOWLObjectProperty(IRI.create(SIO_NS + "has-agent"))
        OWLObjectProperty opHasTarget = dataFactory.getOWLObjectProperty(IRI.create(SIO_NS + "has-target"))
        OWLObjectProperty opHasOutput = dataFactory.getOWLObjectProperty(IRI.create(SIO_NS + "has-output"))
        OWLObjectProperty opIsSuccessorOf = dataFactory.getOWLObjectProperty(IRI.create(SIO_NS + "is-successor-of"))
        OWLDataProperty dpHasValue = dataFactory.getOWLDataProperty(IRI.create(SIO_NS + "has-value"))
        OWLObjectProperty opIncompatibleWith = dataFactory.getOWLObjectProperty(IRI.create(EX_NS + "isIncompatibleWith"))

        manager.addAxiom(ontology, dataFactory.getOWLSubClassOfAxiom(cIdAct, cProcess))
        manager.addAxiom(ontology, dataFactory.getOWLSubClassOfAxiom(cActiveId, cIdAct))
        
        // Hierarchy
        finalClassMap.each { iNatId, iri ->
            OWLClass cls = dataFactory.getOWLClass(iri)
            manager.addAxiom(ontology, dataFactory.getOWLSubClassOfAxiom(cls, cTaxon))
            Integer parentId = iNatParentMap[iNatId]
            if (parentId != null && finalClassMap.containsKey(parentId)) {
                manager.addAxiom(ontology, dataFactory.getOWLSubClassOfAxiom(cls, dataFactory.getOWLClass(finalClassMap[parentId])))
            }
        }
        
        // NCBI Augmentation
        finalClassMap.each { iNatId, iri ->
            if (iri.toString().contains("NCBITaxon")) {
                try {
                    Integer ncbiId = Integer.parseInt(iri.toString().split("_")[1])
                    Integer ncbiParent = ncbiParentMap[ncbiId]
                    if (ncbiParent != null) {
                        OWLClass cls = dataFactory.getOWLClass(iri)
                        OWLClass pCls = dataFactory.getOWLClass(IRI.create(OBO_NS + "NCBITaxon_" + ncbiParent))
                        manager.addAxiom(ontology, dataFactory.getOWLSubClassOfAxiom(cls, pCls))
                        manager.addAxiom(ontology, dataFactory.getOWLSubClassOfAxiom(pCls, cTaxon))
                    }
                } catch (Exception e) {}
            }
        }
        
        // Deep Disjointness
        def getPath = { Integer id ->
            List<Integer> path = []
            Integer curr = id
            while (curr != null) { path.add(0, curr); curr = iNatParentMap[curr] }
            return path
        }
        List<Integer> idList = new ArrayList<>(finalClassMap.keySet())
        int incompatibleCount = 0
        for (int i=0; i<idList.size(); i++) {
            for (int j=i+1; j<idList.size(); j++) {
                Integer t1 = idList[i]; Integer t2 = idList[j]
                List<Integer> path1 = getPath(t1); List<Integer> path2 = getPath(t2)
                if (path1.contains(t2) || path2.contains(t1)) continue
                
                Integer lca = null
                int minLen = Math.min(path1.size(), path2.size())
                for (int k=0; k<minLen; k++) { if (path1[k] == path2[k]) lca = path1[k]; else break }
                
                if (lca != null) {
                    int lcaIdx = path1.indexOf(lca)
                    if (lcaIdx + 1 < path1.size() && lcaIdx + 1 < path2.size()) {
                        // Sibling divergence at LCA children
                        OWLNamedIndividual ind1 = dataFactory.getOWLNamedIndividual(finalClassMap[t1])
                        OWLNamedIndividual ind2 = dataFactory.getOWLNamedIndividual(finalClassMap[t2])
                        manager.addAxiom(ontology, dataFactory.getOWLObjectPropertyAssertionAxiom(opIncompatibleWith, ind1, ind2))
                        manager.addAxiom(ontology, dataFactory.getOWLObjectPropertyAssertionAxiom(opIncompatibleWith, ind2, ind1))
                        incompatibleCount++
                    }
                }
            }
        }
        println "Added ${incompatibleCount} incompatible pairs."

        // SWRL
        def swrlParams = [
            dataFactory.getSWRLClassAtom(cActiveId, dataFactory.getSWRLVariable(IRI.create(EX_NS + "a1"))),
            dataFactory.getSWRLClassAtom(cActiveId, dataFactory.getSWRLVariable(IRI.create(EX_NS + "a2"))),
            dataFactory.getSWRLObjectPropertyAtom(opHasTarget, dataFactory.getSWRLVariable(IRI.create(EX_NS + "a1")), dataFactory.getSWRLVariable(IRI.create(EX_NS + "obs"))),
            dataFactory.getSWRLObjectPropertyAtom(opHasTarget, dataFactory.getSWRLVariable(IRI.create(EX_NS + "a2")), dataFactory.getSWRLVariable(IRI.create(EX_NS + "obs"))),
            dataFactory.getSWRLObjectPropertyAtom(opHasOutput, dataFactory.getSWRLVariable(IRI.create(EX_NS + "a1")), dataFactory.getSWRLVariable(IRI.create(EX_NS + "t1"))),
            dataFactory.getSWRLObjectPropertyAtom(opHasOutput, dataFactory.getSWRLVariable(IRI.create(EX_NS + "a2")), dataFactory.getSWRLVariable(IRI.create(EX_NS + "t2"))),
            dataFactory.getSWRLObjectPropertyAtom(opIncompatibleWith, dataFactory.getSWRLVariable(IRI.create(EX_NS + "t1")), dataFactory.getSWRLVariable(IRI.create(EX_NS + "t2")))
        ]
        def swrlHead = [ dataFactory.getSWRLClassAtom(cConflicting, dataFactory.getSWRLVariable(IRI.create(EX_NS + "obs"))) ]
        manager.addAxiom(ontology, dataFactory.getSWRLRule(new HashSet(swrlParams), new HashSet(swrlHead)))

        // ABox
        observations.each { obs ->
            OWLNamedIndividual obsInd = dataFactory.getOWLNamedIndividual(IRI.create(EX_NS + "obs_" + obs.id))
            manager.addAxiom(ontology, dataFactory.getOWLClassAssertionAxiom(cObservation, obsInd))
            Map<Integer, List> userIds = [:]
            obs.identifications.each { if (it.user) userIds.computeIfAbsent(it.user.id, {[]}).add(it) }
            
            userIds.each { uid, idents ->
                idents.sort { a, b -> a.created_at <=> b.created_at }
                OWLNamedIndividual prevInd = null
                idents.eachWithIndex { ident, index ->
                    OWLNamedIndividual idInd = dataFactory.getOWLNamedIndividual(IRI.create(EX_NS + "id_" + ident.id))
                    manager.addAxiom(ontology, dataFactory.getOWLClassAssertionAxiom(cIdAct, idInd))
                    if (index == idents.size() - 1) manager.addAxiom(ontology, dataFactory.getOWLClassAssertionAxiom(cActiveId, idInd))
                    manager.addAxiom(ontology, dataFactory.getOWLObjectPropertyAssertionAxiom(opHasTarget, idInd, obsInd))
                    
                    if (ident.taxon && finalClassMap.containsKey(ident.taxon.id as Integer)) {
                        manager.addAxiom(ontology, dataFactory.getOWLObjectPropertyAssertionAxiom(opHasOutput, idInd, dataFactory.getOWLNamedIndividual(finalClassMap[ident.taxon.id as Integer])))
                    }
                    
                    if (prevInd != null) manager.addAxiom(ontology, dataFactory.getOWLObjectPropertyAssertionAxiom(opIsSuccessorOf, idInd, prevInd))
                    prevInd = idInd
                }
            }
        }
        return ontology
    }

    void runReasoner(OWLOntology ontology) {
        println "Running HermiT..."
        OWLReasoner reasoner = new ReasonerFactory().createReasoner(ontology)
        println "Consistent? ${reasoner.isConsistent()}"
        def conflicts = reasoner.getInstances(dataFactory.getOWLClass(IRI.create(EX_NS + "ConflictingObservation")), false)
        println "Conflicts: ${conflicts.nodes.size()}"
    }

    void generateReport(OWLOntology ontology, List<Map> observations) {
        println "Total Obs: ${observations.size()}"
        println "Mapped: ${mappedTaxaCount}, Unmapped: ${unmappedTaxaCount}"
    }
}
