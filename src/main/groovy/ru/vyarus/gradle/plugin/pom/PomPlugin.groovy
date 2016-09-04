package ru.vyarus.gradle.plugin.pom

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.ConfigurationContainer
import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.DependencySet
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.publish.maven.plugins.MavenPublishPlugin
import ru.vyarus.gradle.plugin.pom.xml.XmlMerger

/**
 * Pom plugin "fixes" maven-publish plugin pom generation.
 * <p>
 * Plugin must be applied after java or groovy plugin.
 * <p>
 * Applies new configurations:
 * <ul>
 *      <li>provided</li>
 *      <li>optional</li>
 * </ul>
 * Compile configuration extends from both, so build consider all dependencies types as compile.
 * The difference is visible only in resulted pom.
 * <p>
 * During pom generation dependencies scope is automatically fixed for compile dependencies (gradle always
 * set runtime for them) and provided and optional dependencies are also correctly marked.
 * <p>
 * Plugin implicitly activates maven-publish plugin. But publication still must be configured manually:
 * pom plugin only fixes behaviour, but not replace configuration.
 * <p>
 * Plugin adds simplified pom configuration extension. Using pom closure in build new sections could be added
 * to resulted pom. If multiple maven publications configured, pom modification will be applied to all of them.
 *
 * @author Vyacheslav Rusakov
 * @since 04.11.2015
 */
class PomPlugin implements Plugin<Project> {

    private static final String OPTIONAL = 'optional'
    private static final String PROVIDED = 'provided'

    @Override
    void apply(Project project) {
        // activated only when java plugin is enabled
        project.plugins.withType(JavaPlugin) {
            // extensions mechanism not used because we need free closure for pom xml modification
            project.convention.plugins.pom = new PomConvention()

            project.plugins.apply(MavenPublishPlugin)

            addConfigurations(project)
            activatePomModifications(project)
        }
    }

    private void addConfigurations(Project project) {
        ConfigurationContainer configurations = project.configurations
        Configuration provided = configurations.create(PROVIDED)
        provided.setDescription('Provided works the same as compile configuration and only affects resulted pom')

        Configuration optional = configurations.create(OPTIONAL)
        optional.setDescription('Optional works the same as compile configuration and only affects resulted pom')

        configurations.getByName(JavaPlugin.COMPILE_CONFIGURATION_NAME).extendsFrom(provided, optional)
    }

    private void activatePomModifications(Project project) {
        project.afterEvaluate {
            PublishingExtension publishing = project.publishing
            // apply to all configured maven publications
            publishing.publications.withType(MavenPublication) {
                pom.withXml {
                    Node pomXml = asNode()
                    fixPomDependenciesScopes(project, pomXml)
                    applyUserPom(project, pomXml)
                }
            }
        }
    }

    /**
     * Gradle sets runtime scope for all dependencies (actually because it includes everything
     * from runtime configuration) and this has to be fixed.
     * <p>
     * To avoid overriding scope for dependencies directly specified as compile, but also present as
     * transitives in provided or optional, all direct compile dependencies are excluded when looking
     * for optional and provided.
     * <p>
     * NOTE this does not cover cases when compile extends other configurations, but such cases should be rare.
     *
     * @param project project instance
     * @param pomXml pom xml
     */
    private void fixPomDependenciesScopes(Project project, Node pomXml) {
        Closure correctDependencies = { Closure precondition, DependencySet deps, Closure action ->
            pomXml.dependencies.dependency.findAll {
                precondition.call(it) &&
                        deps.find { Dependency dep ->
                            dep.group == it.groupId.text() && dep.name == it.artifactId.text()
                        }
            }.each(action)
        }

        // COMPILE
        Configuration compile = project.configurations.compile
        correctDependencies({ it.scope.text() == JavaPlugin.RUNTIME_CONFIGURATION_NAME },
                compile.allDependencies) {
            it.scope*.value = JavaPlugin.COMPILE_CONFIGURATION_NAME
        }

        // OPTIONAL
        correctDependencies({ true },
                project.configurations.optional.allDependencies - (compile.dependencies) as DependencySet) {
            it.scope*.value = JavaPlugin.COMPILE_CONFIGURATION_NAME
            it.appendNode(OPTIONAL, 'true')
        }

        // PROVIDED
        correctDependencies({ true },
                project.configurations.provided.allDependencies - (compile.dependencies) as DependencySet) {
            it.scope*.value = PROVIDED
        }
    }

    private void applyUserPom(Project project, Node pomXml) {
        PomConvention pomExt = project.convention.plugins.pom
        if (pomExt.config) {
            XmlMerger.mergePom(pomXml, pomExt.config)
        }
        pomExt.xmlModifier?.call(pomXml)
        // apply defaults if required
        if (!pomXml.name) {
            pomXml.appendNode('name', project.name)
        }
        if (project.description && !pomXml.description) {
            pomXml.appendNode('description', project.description)
        }
    }
}
