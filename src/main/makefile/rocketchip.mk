disasm := 2>
which_disasm := $(shell which spike-dasm 2> /dev/null)
ifneq ($(which_disasm),)
        disasm := 3>&1 1>&2 2>&3 | $(which_disasm) $(DISASM_EXTENSION) >
endif

######################
#  Emulation Rules   #
######################
EMUL ?=
SIM_ARGS ?=
output_dir ?=
makefrag ?=

ifeq ($(strip $(EMUL)),)
$(error Define EMUL, emulation binary)
endif
ifeq ($(strip $(output_dir)),)
$(error Define output_dir)
endif
ifeq ($(strip $(makefrag)),)
$(error Define makefrag for emulation rules)
endif

-include $(makefrag)

$(output_dir)/%.run: $(output_dir)/% $(EMUL)
	cd $(dir $(EMUL)) && ./$(notdir $(EMUL)) $< \
	+sample=$<.sample \
	+power=$<-power.csv \
	+toggle=$<-toggle.csv \
	+sample-pwr=$<-sample.csv \
	$(SIM_ARGS) 2> /dev/null 2> $@ && [ $$PIPESTATUS -eq 0 ]
.PRECIOUS: $(output_dir)/%.run

$(output_dir)/%.out: $(output_dir)/% $(EMUL)
	cd $(dir $(EMUL)) && ./$(notdir $(EMUL)) $< \
	+sample=$<.sample \
	+power=$<-power.csv \
	+toggle=$<-toggle.csv \
	+sample-pwr=$<-sample.csv \
	$(SIM_ARGS) $(disasm) $@ && [ $$PIPESTATUS -eq 0 ]
.PRECIOUS: $(output_dir)/%.out

$(output_dir)/%.vpd: $(output_dir)/% $(EMUL)
	cd $(dir $(EMUL)) && ./$(notdir $(EMUL)) $< \
	+vcdplusfile=$@ \
	+sample=$<.sample \
	+power=$<-power.csv \
	+toggle=$<-toggle.csv \
	+sample-pwr=$<-sample.csv \
	$(SIM_ARGS) $(disasm) $(patsubst %.vpd,%.out,$@) && [ $$PIPESTATUS -eq 0 ]
.PRECIOUS: $(output_dir)/%.vpd

$(output_dir)/%.vcd: $(output_dir)/% $(EMUL)
	cd $(dir $(EMUL)) && ./$(notdir $(EMUL)) $< \
	+vcdfile=$@ \
	+sample=$<.sample \
	+power=$<-power.csv \
	+toggle=$<-toggle.csv \
	+sample-pwr=$<-sample.csv \
	$(SIM_ARGS) $(disasm) $(patsubst %.vcd,%.out,$@) && [ $$PIPESTATUS -eq 0 ]
.PRECIOUS: $(output_dir)/%.vcd
