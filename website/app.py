import os
import json
import subprocess
import threading
import time
import requests
from flask import Flask, render_template, request, jsonify

app = Flask(__name__)

# Configuration
SPARQL_ENDPOINT = "http://localhost:8890/sparql"
BASE_GRAPH_URI = "https://rub-al-khali.bio2vec.net/consensus/"
PROJECT_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..'))

# In-memory state for job status
# { "slug": { "status": "building" | "ready" | "error", "msg": "...", "timestamp": ... } }
project_status = {}
active_projects = []

def get_graph_uri(slug):
    return f"{BASE_GRAPH_URI}{slug}"

def run_pipeline(slug):
    """
    Background task to:
    1. Run Gradle build for the specific project slug.
    2. Tell Virtuoso to load the new file into a named graph.
    """
    try:
        project_status[slug] = {"status": "building", "msg": "Fetching data and building ontology..."}
        
        # 1. Run Gradle
        # We run from the project root.
        # Command: ./project/gradlew run --args="slug" -p project
        # Actually easier to cd into project dir
        project_dir = os.path.join(PROJECT_ROOT, 'project')
        cmd = ["./gradlew", "run", f"--args={slug}"]
        
        print(f"[{slug}] Starting build in {project_dir}...")
        process = subprocess.run(
            cmd, 
            cwd=project_dir, 
            capture_output=True, 
            text=True
        )
        
        if process.returncode != 0:
            print(f"[{slug}] Build failed:\n{process.stderr}")
            project_status[slug] = {"status": "error", "msg": "Gradle build failed. Check logs."}
            return

        print(f"[{slug}] Build complete.")
        project_status[slug] = {"status": "loading", "msg": "Loading into Knowledge Graph..."}

        # 2. Load into Virtuoso
        # File path relative to /data in container: /data/projects_data/<slug>.owl
        # (Assuming ./project is mounted to /data)
        
        owl_file = f"{slug}.owl"
        graph_uri = get_graph_uri(slug)
        
        # Create SQL script in project folder (mounted as /data)
        sql_file_path = os.path.join(PROJECT_ROOT, 'project', 'load.sql')
        try:
            with open(sql_file_path, 'w') as f:
                f.write(f"SPARQL CLEAR GRAPH <{graph_uri}>;\n")
                f.write(f"DELETE FROM DB.DBA.LOAD_LIST WHERE ll_file LIKE '%{owl_file}';\n")
                f.write(f"ld_dir('/data/projects_data', '{owl_file}', '{graph_uri}');\n")
                f.write("rdf_loader_run();\n")
                f.write("checkpoint;\n")
                f.write("EXIT;\n")
        except Exception as e:
            print(f"[{slug}] Error writing SQL script: {e}")
            project_status[slug] = {"status": "error", "msg": "Internal Error (SQL generation)"}
            return

        print(f"[{slug}] Running ISQL batch script...")
        full_cmd = [
            "docker-compose", 
            "exec", "-T", "virtuoso", 
            "isql-v", "1111", "dba", "dba", 
            "/data/load.sql"
        ]
        
        try:
            subprocess.run(full_cmd, cwd=PROJECT_ROOT, check=True)
            print(f"[{slug}] Ready.")
            project_status[slug] = {"status": "ready", "msg": "Project loaded successfully."}
            if slug not in active_projects:
                active_projects.append(slug)
        except subprocess.CalledProcessError as e:
             print(f"[{slug}] ISQL Error: {e}")
             project_status[slug] = {"status": "error", "msg": "Knowledge Graph Load Failed."}

    except Exception as e:
        print(f"[{slug}] Error: {e}")
        project_status[slug] = {"status": "error", "msg": str(e)}

def init_existing_projects():
    """
    Check Virtuoso for existing graphs on startup.
    """
    print("Checking for existing projects in Knowledge Graph...")
    query = """
    SELECT DISTINCT ?g WHERE {
      GRAPH ?g { ?s ?p ?o }
      FILTER (STRSTARTS(STR(?g), "https://rub-al-khali.bio2vec.net/consensus/"))
    }
    """
    try:
        response = requests.get(SPARQL_ENDPOINT, params={'query': query, 'format': 'json'})
        if response.status_code == 200:
            bindings = response.json()['results']['bindings']
            for b in bindings:
                g = b['g']['value']
                slug = g.replace(BASE_GRAPH_URI, "")
                if slug and slug not in active_projects:
                    active_projects.append(slug)
                    project_status[slug] = {"status": "ready", "msg": "Loaded from existing graph."}
            print(f"Found existing projects: {active_projects}")
    except:
        print("Could not connect to Virtuoso on startup. Is it running?")

@app.route('/')
def index():
    return render_template('index.html')

@app.route('/projects', methods=['GET'])
def list_projects():
    return jsonify({
        "active": active_projects,
        "status": project_status
    })

@app.route('/projects', methods=['POST'])
def add_project():
    data = request.json
    slug = data.get('slug')
    if not slug:
        return jsonify({"error": "slug required"}), 400
    
    # Sanitize slug a bit
    slug = "".join(c for c in slug if c.isalnum() or c in "-_").lower()
    
    if slug in project_status and project_status[slug]['status'] == 'building':
        return jsonify({"message": "Project already building", "slug": slug})

    # Start background thread
    t = threading.Thread(target=run_pipeline, args=(slug,))
    t.start()
    
    return jsonify({"message": "Build started", "slug": slug})

@app.route('/query', methods=['POST'])
def query():
    data = request.json
    sparql_query = data.get('query')
    project_slug = data.get('project') # Optional: if provided, we restrict to graph?
    
    if not sparql_query:
        return jsonify({'error': 'No query provided'}), 400
    
    # If a project is specified, we can prepend `FROM <graph>` or let the query handle it.
    # To keep it simple, the frontend will likely send the project context.
    # But if the user wants to "Visualize" a specific project, the frontend knows which one.
    # We can inject "FROM <http://example.org/biodiversity/slug>" if it's missing?
    # Better: The frontend selects the project, and we inject it here to ensure isolation.
    
    final_query = sparql_query
    
    # Basic injection (only if SELECT query and no FROM clause exists)
    if project_slug:
        graph_uri = get_graph_uri(project_slug)
        # Check if FROM is already there
        if "FROM <" not in sparql_query and "select" in sparql_query.lower():
            # Inject after SELECT
            # Simple hack, robust enough for this demo
            # Actually, standard SPARQL: SELECT ... FROM <g> WHERE ...
            # We will just prepend "FROM <g>" to the WHERE clause logic if feasible, 
            # or rely on the query being written correctly.
            # Let's try passing 'default-graph-uri' param to Virtuoso instead!
            pass

    params = {'query': final_query, 'format': 'json'}
    if project_slug:
        params['default-graph-uri'] = get_graph_uri(project_slug)

    try:
        response = requests.get(
            SPARQL_ENDPOINT, 
            params=params,
            timeout=20
        )
        # Virtuoso sends 200 even on some errors, need to check content type or parse
        if response.status_code >= 400:
            return jsonify({'error': response.text}), response.status_code
            
        return jsonify(response.json())
    except Exception as e:
        return jsonify({'error': str(e)}), 500

if __name__ == '__main__':
    # Try to init existing projects in a separate thread to not block startup if DB is slow
    threading.Thread(target=init_existing_projects).start()
    app.run(debug=True, port=5000, host='0.0.0.0')