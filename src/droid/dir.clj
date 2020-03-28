(ns droid.dir
  )


(defn get-workspace-dir
  "Given a project and optionally a branch, generate the appropriate workspace path"
  ([project]
   (str "projects/" project "/workspace/"))
  ([project branch]
   (str "projects/" project "/workspace/" branch "/")))


(defn get-temp-dir
  "Given a project and optionally a branch, generate the appropriate temp path"
  ([project]
   (str "projects/" project "/temp/"))
  ([project branch]
   (str "projects/" project "/temp/" branch "/")))
