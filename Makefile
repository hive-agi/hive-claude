CLEL := clel
SRC_DIR := src/cljel
OUT_DIR := out

.PHONY: compile clean watch

compile:
	CLEL_HOME=/home/lages/PP/clojure-elisp $(CLEL) compile $(SRC_DIR) -o $(OUT_DIR)

watch:
	CLEL_HOME=/home/lages/PP/clojure-elisp $(CLEL) watch $(SRC_DIR) -o $(OUT_DIR)

clean:
	rm -rf $(OUT_DIR)/*.el $(OUT_DIR)/**/*.el
