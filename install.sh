#!/usr/bin/env sh

DROID_DIR=$(dirname $(realpath $0))
TMPFILE=/tmp/droid-install-log.$$

trap '/bin/rm -f $TMPFILE; exit 1' 1 2 15

lein uberjar | tee $TMPFILE
JARFILE=$(grep -P "^Created.*droid-.*standalone.jar$" $TMPFILE | awk '{print $2}')

echo
echo "Generating DROID wrapper ..."

echo "\
#!/usr/bin/env sh

cd $DROID_DIR
java -jar $JARFILE \$*" > $DROID_DIR/droid
chmod u+x $DROID_DIR/droid

rm -f $TMPFILE

echo "Done. DROID wrapper generated in $DROID_DIR/droid."
echo "Make sure to include $DROID_DIR in your environment variable PATH before running droid."
