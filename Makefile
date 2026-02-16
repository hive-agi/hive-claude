CLEL := clel
SRC_DIR := src/cljel
OUT_DIR := out
ELISP_DIR := resources/elisp

.PHONY: compile clean watch

compile:
	CLEL_HOME=/home/lages/PP/clojure-elisp $(CLEL) compile $(SRC_DIR) -o $(OUT_DIR)
	@mkdir -p $(ELISP_DIR)
	@for f in $(OUT_DIR)/hive_claude/*.el; do \
		base=$$(basename "$$f" .el); \
		target="hive-claude-$$base.el"; \
		cp "$$f" "$(ELISP_DIR)/$$target"; \
	done
	@echo "Copied elisp to $(ELISP_DIR)/ with feature-matching names"

watch:
	CLEL_HOME=/home/lages/PP/clojure-elisp $(CLEL) watch $(SRC_DIR) -o $(OUT_DIR)

clean:
	rm -rf $(OUT_DIR)/*.el $(OUT_DIR)/**/*.el $(ELISP_DIR)/*.el
