### Test Makefile for DROID

### Workflow
#
# 1. Review the [Knocean Practises Document](https://github.com/knocean/practises)
# 2. Run [Clean](clean) to clean the contents of the build/ directory.
# 2. Run [Update](update) to update the contents of the build/ directory.
# 3. View the results:
#     - [Updated Build](build/update.txt)

.PHONY: update clean
update: build/update.txt

clean:
	rm -f build/*
	rmdir build

build/update.txt: | build
	@echo "Sleeping for 10 seconds ..."
	@sleep 10
	@echo "Writing to the build directory ..."
	@echo "This is a test build file" > build/update.txt
	@echo "Done."

build:
	mkdir -p $@
