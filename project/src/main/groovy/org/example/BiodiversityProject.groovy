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
    static final String EX_NS = "https://rub-al-khali.bio2vec.net/consensus/"
    static final String OBO_NS = "http://purl.obolibrary.org/obo/"
    static final String ENVO_NS = "http://purl.obolibrary.org/obo/"
    
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
        
        // 2b. Process Environment (ENVO)
        processEnvironment(observations)
        
        // 3. Build Ontology
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager()
        dataFactory = manager.getOWLDataFactory()
        
        OWLOntology ontology = buildOntology(manager, observations)
        
        // 5. Reasoning
        runReasoner(manager, ontology)

        // 4. Save Ontology (Specific to project)
        File dataDir = new File("projects_data")
        if (!dataDir.exists()) dataDir.mkdirs()
        
        File callbackFile = new File(dataDir, "${projectSlug}.owl")
        manager.saveOntology(ontology, IRI.create(callbackFile.toURI()))
        println "Ontology saved to ${callbackFile.absolutePath}"

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
        int skippedTaxaCount = 0
        allINatIds.each { id ->
            String name = iNatIdToName[id]
            if (name && nameToNcbiId.containsKey(name)) {
                String ncbiStr = nameToNcbiId[name]
                String justId = ncbiStr.split(":")[1]
                finalClassMap[id] = IRI.create(OBO_NS + "NCBITaxon_" + justId)
                mappedTaxaCount++
            } else {
                if (name) {
                    finalClassMap[id] = IRI.create(EX_NS + "iNatTaxon_" + id)
                    unmappedTaxaCount++
                } else {
                    skippedTaxaCount++
                }
            }
        }
        println "Mapping complete. Mapped: ${mappedTaxaCount}, Unmapped: ${unmappedTaxaCount}, Skipped (no name): ${skippedTaxaCount}"
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
        OWLObjectProperty opIsLocatedIn = dataFactory.getOWLObjectProperty(IRI.create(SIO_NS + "is-located-in"))
        OWLDataProperty dpHasValue = dataFactory.getOWLDataProperty(IRI.create(SIO_NS + "has-value"))
        OWLObjectProperty opIncompatibleWith = dataFactory.getOWLObjectProperty(IRI.create(EX_NS + "isIncompatibleWith"))
        OWLDataProperty dpAsWKT = dataFactory.getOWLDataProperty(IRI.create("http://www.opengis.net/ont/geosparql#asWKT"))
        OWLDatatype wktLiteral = dataFactory.getOWLDatatype(IRI.create("http://www.opengis.net/ont/geosparql#wktLiteral"))

        manager.addAxiom(ontology, dataFactory.getOWLSubClassOfAxiom(cIdAct, cProcess))
        manager.addAxiom(ontology, dataFactory.getOWLSubClassOfAxiom(cActiveId, cIdAct))
        
        // Hierarchy
        finalClassMap.each { iNatId, iri ->
            OWLClass cls = dataFactory.getOWLClass(iri)
            manager.addAxiom(ontology, dataFactory.getOWLSubClassOfAxiom(cls, cTaxon))
            
            // Add Label
            String name = iNatIdToName[iNatId]
            if (name) {
                 manager.addAxiom(ontology, dataFactory.getOWLAnnotationAssertionAxiom(dataFactory.getRDFSLabel(), iri, dataFactory.getOWLLiteral(name)))
            }

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
            
            // Location (GeoSPARQL)
            if (obs.location) {
                try {
                    String[] parts = obs.location.split(",") // "lat,lng"
                    if (parts.length == 2) {
                        String lat = parts[0].trim()
                        String lng = parts[1].trim()
                        String wkt = "POINT(${lng} ${lat})"
                        manager.addAxiom(ontology, dataFactory.getOWLDataPropertyAssertionAxiom(dpAsWKT, obsInd, dataFactory.getOWLLiteral(wkt, wktLiteral)))
                    }
                } catch (Exception e) { println "Error parsing location for ${obs.id}: ${e}" }
            }
            
            // ENVO Link (Strategy 1 + 2)
            if (obs.envoClass) {
                 OWLClass cEnv = dataFactory.getOWLClass(IRI.create(obs.envoClass))
                 OWLNamedIndividual envInd = dataFactory.getOWLNamedIndividual(IRI.create(EX_NS + "env_" + obs.id))
                 manager.addAxiom(ontology, dataFactory.getOWLClassAssertionAxiom(cEnv, envInd))
                 if (obs.envoLabel) {
                     manager.addAxiom(ontology, dataFactory.getOWLAnnotationAssertionAxiom(dataFactory.getRDFSLabel(), envInd.getIRI(), dataFactory.getOWLLiteral(obs.envoLabel)))
                 }
                 manager.addAxiom(ontology, dataFactory.getOWLObjectPropertyAssertionAxiom(opIsLocatedIn, obsInd, envInd))
            }

            Map<Integer, List> userIds = [:]
            obs.identifications.each { if (it.user) userIds.computeIfAbsent(it.user.id, {[]}).add(it) }
            
            userIds.each { uid, idents ->
                // Materialize Agent
                OWLNamedIndividual agentInd = dataFactory.getOWLNamedIndividual(IRI.create(EX_NS + "user_" + uid))
                manager.addAxiom(ontology, dataFactory.getOWLClassAssertionAxiom(cAgent, agentInd))
                
                // Add Label (User Login)
                if (idents && idents[0].user && idents[0].user.login) {
                    String login = idents[0].user.login
                    manager.addAxiom(ontology, dataFactory.getOWLAnnotationAssertionAxiom(dataFactory.getRDFSLabel(), agentInd.getIRI(), dataFactory.getOWLLiteral(login)))
                }
                
                idents.sort { a, b -> a.created_at <=> b.created_at }
                OWLNamedIndividual prevInd = null
                idents.eachWithIndex { ident, index ->
                    OWLNamedIndividual idInd = dataFactory.getOWLNamedIndividual(IRI.create(EX_NS + "id_" + ident.id))
                    manager.addAxiom(ontology, dataFactory.getOWLClassAssertionAxiom(cIdAct, idInd))
                    if (index == idents.size() - 1) manager.addAxiom(ontology, dataFactory.getOWLClassAssertionAxiom(cActiveId, idInd))
                    manager.addAxiom(ontology, dataFactory.getOWLObjectPropertyAssertionAxiom(opHasTarget, idInd, obsInd))
                    manager.addAxiom(ontology, dataFactory.getOWLObjectPropertyAssertionAxiom(opHasAgent, idInd, agentInd))

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

    void runReasoner(OWLOntologyManager manager, OWLOntology ontology) {
        println "Running HermiT..."
        OWLReasoner reasoner = new ReasonerFactory().createReasoner(ontology)
        println "Consistent? ${reasoner.isConsistent()}"
        OWLClass cConflicting = dataFactory.getOWLClass(IRI.create(EX_NS + "ConflictingObservation"))
        def conflicts = reasoner.getInstances(cConflicting, false)
        println "Conflicts: ${conflicts.nodes.size()}"
        
        conflicts.nodes.each { node ->
             node.entities.each { ind ->
                 println "Conflict found: ${ind.getIRI()}"
                 manager.addAxiom(ontology, dataFactory.getOWLClassAssertionAxiom(cConflicting, ind))
             }
        }
    }

    void generateReport(OWLOntology ontology, List<Map> observations) {
        println "Total Obs: ${observations.size()}"
        println "Mapped: ${mappedTaxaCount}, Unmapped: ${unmappedTaxaCount}"
    }

    void processEnvironment(List<Map> observations) {
        println "Processing environment context (ENVO) for slug: '${projectSlug}'..."
        EnvoMapper mapper = new EnvoMapper()
        int count = 0
        int total = observations.size()
        int processed = 0
        
        observations.each { obs ->
            processed++
            if (processed % 10 == 0) println "Processing environment: ${processed}/${total} (Linked: ${count})"
            
            if (obs.location) {
                try {
                    String[] parts = obs.location.split(",")
                    if (parts.length == 2) {
                        double lat = Double.parseDouble(parts[0].trim())
                        double lng = Double.parseDouble(parts[1].trim())
                        Map res = mapper.get(lat, lng, projectSlug)
                        if (res) {
                            obs.envoClass = res.iri
                            obs.envoLabel = res.label
                            count++
                        }
                    }
                } catch (Exception e) {
                    // println "Error parsing location for obs ${obs.id}: ${e}"
                }
            }
        }
        println "Linked ${count} observations to ENVO environments."
    }

    static class EnvoMapper {
        File cacheFile = new File("envo_cache.json")
        Map cache = [:]
        
        // Priority Mapping (Top matches first)
        Map<String, Map> mappings = [
            'natural=water': [iri: 'http://purl.obolibrary.org/obo/ENVO_00000063', label: 'Water Body'],
            'natural=wetland': [iri: 'http://purl.obolibrary.org/obo/ENVO_00000043', label: 'Wetland'],
            'natural=wood': [iri: 'http://purl.obolibrary.org/obo/ENVO_00000111', label: 'Forest'],
            'landuse=forest': [iri: 'http://purl.obolibrary.org/obo/ENVO_00000111', label: 'Forest'],
            'natural=scrub': [iri: 'http://purl.obolibrary.org/obo/ENVO_00000302', label: 'Shrubland'],
            'natural=sand': [iri: 'http://purl.obolibrary.org/obo/ENVO_00000115', label: 'Sand Desert'],
            'natural=desert': [iri: 'http://purl.obolibrary.org/obo/ENVO_00000098', label: 'Desert'],
            'natural=bare_rock': [iri: 'http://purl.obolibrary.org/obo/ENVO_00000014', label: 'Rock'],
            'landuse=residential': [iri: 'http://purl.obolibrary.org/obo/ENVO_01000248', label: 'Urban Area'],
            'place=city': [iri: 'http://purl.obolibrary.org/obo/ENVO_01000248', label: 'Urban Area']
        ]

        EnvoMapper() {
            if (cacheFile.exists()) {
                try { cache = new JsonSlurper().parse(cacheFile) } catch (e) {}
            }
        }

        Map get(double lat, double lon, String projectSlug) {
            String key = String.format(Locale.US, "%.3f,%.3f", lat, lon)
            Map result = null
            boolean fromCache = false

            if (cache.containsKey(key)) {
                result = cache[key]
                fromCache = true
            }

            if (result == null && !fromCache) {
                 result = fetchOverpass(lat, lon)
                 sleep(1000) // Rate limit for fresh fetches
            }
            
            // Strategy 1 Fallback
            if (result == null && projectSlug == "rub-al-khali") {
                result = [iri: 'http://purl.obolibrary.org/obo/ENVO_00000115', label: 'Sand Desert']
            }

            if (result == null) {
                // println "DEBUG: Failed to resolve ${key}. Slug: '${projectSlug}'"
            }
            
            // Save back to cache if it wasn't there or if we just patched a null value
            if (!fromCache || (fromCache && cache[key] == null && result != null)) {
                cache[key] = result
                saveCache()
            }
            
            return result
        }

        void saveCache() {
             cacheFile.setText(groovy.json.JsonOutput.toJson(cache))
        }

        Map fetchOverpass(double lat, double lon) {
            // Query for features using is_in (areas) and around (nearby features)
            String query = """
                [out:json];
                is_in(${lat},${lon})->.a;
                (
                  way(around:100,${lat},${lon})["natural"];
                  way(around:100,${lat},${lon})["landuse"];
                  area.a["natural"];
                  area.a["landuse"];
                );
                out tags;
            """
            try {
                String url = "https://overpass-api.de/api/interpreter"
                def conn = new URL(url).openConnection()
                conn.doOutput = true
                conn.requestMethod = "POST"
                conn.outputStream.withWriter { it.write("data=" + java.net.URLEncoder.encode(query, "UTF-8")) }
                
                if (conn.responseCode == 200) {
                    def json = new JsonSlurper().parse(conn.inputStream)
                    if (json.elements) {
                        for (def el : json.elements) {
                            if (!el.tags) continue
                            // Check against mappings
                            for (String tagKey : mappings.keySet()) {
                                String[] kv = tagKey.split("=")
                                if (el.tags[kv[0]] == kv[1]) {
                                    return mappings[tagKey]
                                }
                            }
                        }
                    }
                }
            } catch (Exception e) {
                println "Overpass Error: ${e.message}"
            }
            return null
        }
    }
}
