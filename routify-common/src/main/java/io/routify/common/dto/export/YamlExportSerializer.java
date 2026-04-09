package io.routify.common.dto.export;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;
import org.yaml.snakeyaml.representer.Representer;

import java.util.Map;

/**
 * SnakeYAML helper for serialising and deserialising {@link GatewayExportV1} documents.
 *
 * <p>Uses block-style formatting for human readability and explicit newline handling
 * suitable for version-controlled YAML files.
 */
public final class YamlExportSerializer {

    private YamlExportSerializer() {}

    /**
     * Serialise a raw map (produced from Jackson or manual assembly) to a YAML string
     * with block-style formatting.
     */
    public static String toYaml(Map<String, Object> document) {
        DumperOptions options = newDumperOptions();
        Yaml yaml = new Yaml(options);
        return yaml.dump(document);
    }

    /**
     * Deserialise a YAML string into a raw map for further processing.
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> fromYaml(String yamlContent) {
        LoaderOptions loaderOptions = new LoaderOptions();
        loaderOptions.setMaxAliasesForCollections(50);
        Representer representer = new Representer(newDumperOptions());
        Yaml yaml = new Yaml(new Constructor(loaderOptions), representer);
        return yaml.loadAs(yamlContent, Map.class);
    }

    private static DumperOptions newDumperOptions() {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);
        options.setIndent(2);
        options.setIndicatorIndent(0);
        options.setWidth(120);
        return options;
    }
}

