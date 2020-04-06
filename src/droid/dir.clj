(ns droid.dir)

(defn get-workspace-dir
  "Given a project and optionally a branch name, generate the appropriate workspace path"
  ([project-name]
   (str "projects/" project-name "/workspace/"))
  ([project-name branch-name]
   (str "projects/" project-name "/workspace/" branch-name "/")))

(defn get-temp-dir
  "Given a project and optionally a branch name, generate the appropriate temp path"
  ([project-name]
   (str "projects/" project-name "/temp/"))
  ([project-name branch-name]
   (str "projects/" project-name "/temp/" branch-name "/")))
