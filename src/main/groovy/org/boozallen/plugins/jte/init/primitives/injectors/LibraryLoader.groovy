/*
 Copyright 2018 Booz Allen Hamilton
 Licensed under the Apache License, Version 2.0 (the "License");
 you may not use this file except in compliance with the License.
 You may obtain a copy of the License at
 http://www.apache.org/licenses/LICENSE-2.0
 Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an "AS IS" BASIS,
 WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 See the License for the specific language governing permissions and
 limitations under the License.
 */
package org.boozallen.plugins.jte.init.primitives.injectors

import com.cloudbees.groovy.cps.NonCPS
import hudson.Extension
import jenkins.model.Jenkins
import org.boozallen.plugins.jte.init.dsl.PipelineConfigurationObject
import org.boozallen.plugins.jte.init.dsl.TemplateConfigException
import org.boozallen.plugins.jte.init.governance.GovernanceTier
import org.boozallen.plugins.jte.init.governance.libs.LibraryProvider
import org.boozallen.plugins.jte.init.governance.libs.LibrarySource
import org.boozallen.plugins.jte.init.primitives.TemplateBinding
import org.boozallen.plugins.jte.init.primitives.TemplatePrimitiveInjector
import org.boozallen.plugins.jte.util.RunUtils
import org.boozallen.plugins.jte.util.TemplateLogger
import org.boozallen.plugins.jte.util.TemplateScriptEngine
import org.jenkinsci.plugins.workflow.cps.CpsScript
import org.jenkinsci.plugins.workflow.flow.FlowExecutionOwner
import org.jenkinsci.plugins.workflow.job.WorkflowJob

@Extension public class LibraryLoader extends TemplatePrimitiveInjector {

    @NonCPS
    static void doInject(FlowExecutionOwner flowOwner, PipelineConfigurationObject config, Binding binding){
        // 1. Inject steps from loaded libraries
        WorkflowJob job = flowOwner.run().getParent()
        List<GovernanceTier> tiers = GovernanceTier.getHierarchy(job)
        List<LibrarySource> libs = tiers.collect{ it.getLibrarySources() }.flatten().minus(null)
        List<LibraryProvider> providers = libs.collect{ it.getLibraryProvider() }.flatten().minus(null)

        ArrayList libConfigErrors = []
        config.getConfig().libraries.each{ libName, libConfig ->
            LibraryProvider p = providers.find{ it.hasLibrary(flowOwner, libName) }
            if (!p){
                libConfigErrors << "Library ${libName} Not Found."
            } else {
                libConfigErrors << p.loadLibrary(flowOwner, binding, libName, libConfig)
            }
        }
        libConfigErrors = libConfigErrors.flatten().minus(null)

        TemplateLogger logger = new TemplateLogger(flowOwner.getListener())

        // if library errors were found:
        if(libConfigErrors){
            logger.printError("----------------------------------")
            logger.printError("   Library Configuration Errors   ")
            logger.printError("----------------------------------")
            libConfigErrors.each{ line ->
                logger.printError(line)
            }
            logger.printError("----------------------------------")
            throw new TemplateConfigException("There were library configuration errors.")
        }

        // 2. Inject steps with default step implementation for configured steps
        config.getConfig().steps.findAll{ stepName, stepConfig ->
            if (binding.hasStep(stepName)){
                ArrayList msg = [
                    "Configured step ${stepName} ignored.",
                    "-- Loaded by the ${binding.getStep(stepName).library} Library."
                ]
                logger.printWarning msg.join("\n")
                return false
            }
            return true
        }.each{ stepName, stepConfig ->
            logger.print "Creating step ${stepName} from the default step implementation."
            def stepWrapper = LibraryLoader.getPrimitiveClass()
            binding.setVariable(stepName, stepWrapper.createDefaultStep(script, stepName, stepConfig))
        }
        println "DEBUG: Finished Injecting Libraries"
    }

    static void doPostInject(FlowExecutionOwner flowOwner, PipelineConfigurationObject config, Binding binding){
        // 3. Inject a passthrough step for steps not defined (either as steps or other primitives)
        config.getConfig().template_methods.findAll{ step ->
            !(step.key in binding.registry)
        }.each{ step ->
            def stepWrapper = LibraryLoader.getPrimitiveClass()
            binding.setVariable(step.key, stepWrapper.createNullStep(step.key, script))
        }
    }

    static Class getPrimitiveClass(){
        ClassLoader uberClassLoader = Jenkins.get().pluginManager.uberClassLoader
        String self = this.getMetaClass().getTheClass().getName()
        String classText = uberClassLoader.loadClass(self).getResource("StepWrapper.groovy").text
        return TemplateScriptEngine.parseClass(classText)
    }

}
