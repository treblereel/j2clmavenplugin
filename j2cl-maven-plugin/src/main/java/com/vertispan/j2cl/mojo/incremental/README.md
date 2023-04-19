* remember to make turbine incremental, if it possible
* Do not forget to copy public resources
* don't forget to add resources, we must trigger apt if, for instance, template.html was changed!
* clean bundle.js and transpile.js from generated sources
* restore after failing to build
* schedule a rebuild if build is on progress

* don't run bytecode task for deps (well, apt)
* run turbine is sources.isEmtpy() and javac if not 
