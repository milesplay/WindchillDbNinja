import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;

/** Local packaging helper; never loaded by Windchill. */
public final class ConfigurationFiles {
    private static final String SERVICE =
        "com.custom.dbcapture.DbCaptureService/com.custom.dbcapture.StandardDbCaptureService";
    private static final Set<String> SETTINGS = Set.of(
        "com.custom.dbcapture.excludeTables", "com.custom.dbcapture.maxRowsPerTable",
        "com.custom.dbcapture.correlateLogs");

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            throw new IllegalArgumentException(
                "Usage: properties|role|migrate|references|check|wex-models|wex-resolve input output-or-keys [target-navigation-xml]");
        }
        Path input = Path.of(args[1]);
        if ("properties".equals(args[0])) {
            Properties properties = new Properties();
            try (var stream = Files.newInputStream(input)) {
                properties.load(stream);
            }
            for (String key : args[2].split(",")) {
                if (properties.containsKey(key)) {
                    String value = Base64.getEncoder().encodeToString(
                        properties.getProperty(key).getBytes(StandardCharsets.UTF_8));
                    System.out.println(key + "=" + value);
                }
            }
            return;
        }
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        var builder = factory.newDocumentBuilder();
        builder.setEntityResolver((publicId, systemId) -> new InputSource(new StringReader("")));
        Document document = builder.parse(input.toFile());
        Element root = document.getDocumentElement();
        if ("check".equals(args[0])) {
            return;
        }
        if ("wex-resolve".equals(args[0])) {
            if (args.length != 4) throw new IllegalArgumentException("Supply the target navigation-actionModels.xml.");
            resolveWexModels(document, builder.parse(Path.of(args[3]).toFile()));
        } else if ("wex-models".equals(args[0])) {
            prepareWexModels(root);
        } else if ("role".equals(args[0])) {
            mergeRole(document, root);
        } else if ("migrate".equals(args[0]) || "references".equals(args[0])) {
            if (!"Configuration".equals(root.getTagName())) {
                throw new IllegalArgumentException("Expected a Configuration root: " + input);
            }
            for (Node node = root.getFirstChild(); node != null;) {
                Node next = node.getNextSibling();
                if (node instanceof Element) {
                    Element element = (Element) node;
                    if ("migrate".equals(args[0]) ? ownedDeclaration(element) : ownedReference(element)) {
                        root.removeChild(node);
                    }
                }
                node = next;
            }
        } else {
            throw new IllegalArgumentException("Unknown operation: " + args[0]);
        }
        var transformers = TransformerFactory.newInstance();
        transformers.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        transformers.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        transformers.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
        var transformer = transformers.newTransformer();
        transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        if (document.getDoctype() != null) {
            if (document.getDoctype().getInternalSubset() != null) {
                throw new IllegalArgumentException("Review internal DTD subsets manually: " + input);
            }
            transformer.setOutputProperty(OutputKeys.DOCTYPE_SYSTEM,
                "wex-resolve".equals(args[0]) ? "actionmodels.dtd" : document.getDoctype().getSystemId());
        }
        if ("wex-models".equals(args[0]) || "wex-resolve".equals(args[0])) {
            try (var stream = Files.newOutputStream(Path.of(args[2]), java.nio.file.StandardOpenOption.CREATE_NEW)) {
                transformer.transform(new DOMSource(document), new StreamResult(stream));
            }
        } else {
            transformer.transform(new DOMSource(document), new StreamResult(Path.of(args[2]).toFile()));
        }
    }

    private static void resolveWexModels(Document document, Document baseline) {
        Element root = document.getDocumentElement();
        Element baseRoot = baseline.getDocumentElement();
        if (!"actionmodels".equals(baseRoot.getTagName())) {
            throw new IllegalArgumentException("Expected target actionmodels root.");
        }
        Map<String, Element> additions = new LinkedHashMap<>();
        for (Node node = root.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element model && "model".equals(model.getTagName())
                    && Set.of("header actions", "site navigation").contains(model.getAttribute("name"))) {
                additions.put(model.getAttribute("name"), model);
            }
        }
        prepareWexModels(root);
        for (var addition : additions.entrySet()) {
            Element baseModel = null;
            for (Node node = baseRoot.getFirstChild(); node != null; node = node.getNextSibling()) {
                if (node instanceof Element model && "model".equals(model.getTagName())
                        && addition.getKey().equals(model.getAttribute("name"))) {
                    if (baseModel != null) throw new IllegalArgumentException("Duplicate target model: " + addition.getKey());
                    baseModel = model;
                }
            }
            if (baseModel == null) throw new IllegalArgumentException("Missing target model: " + addition.getKey());
            Element resolved = (Element) document.importNode(baseModel, true);
            resolved.removeAttribute("incremental");
            if (!resolved.hasAttribute("resourceBundle") && baseRoot.hasAttribute("resourceBundle")) {
                resolved.setAttribute("resourceBundle", baseRoot.getAttribute("resourceBundle"));
            }
            Map<String, Element> inserted = new LinkedHashMap<>();
            for (Node node = addition.getValue().getFirstChild(); node != null; node = node.getNextSibling()) {
                if (!(node instanceof Element action)) continue;
                String anchorName = action.getAttribute("insertAfterActionName");
                String anchorType = action.getAttribute("insertAfterObjectType");
                Element anchor = inserted.get(anchorType + "/" + anchorName);
                for (Node item = resolved.getFirstChild(); item != null; item = item.getNextSibling()) {
                    if (item instanceof Element existing && "action".equals(existing.getTagName())) {
                        if ("dbcapture".equals(existing.getAttribute("type"))
                                && action.getAttribute("name").equals(existing.getAttribute("name"))) {
                            throw new IllegalArgumentException("Target already contains DB Ninja action: " + action.getAttribute("name"));
                        }
                        if (anchor == null && anchorName.equals(existing.getAttribute("name"))
                                && anchorType.equals(existing.getAttribute("type"))) anchor = existing;
                    }
                }
                if (anchorName.isEmpty() || anchorType.isEmpty() || anchor == null) {
                    throw new IllegalArgumentException("Missing target action anchor: " + anchorType + "/" + anchorName);
                }
                Element injected = (Element) document.importNode(action, true);
                injected.removeAttribute("insertAfterActionName");
                injected.removeAttribute("insertAfterObjectType");
                resolved.insertBefore(injected, anchor.getNextSibling());
                inserted.put(injected.getAttribute("type") + "/" + injected.getAttribute("name"), injected);
            }
            root.appendChild(resolved);
        }
    }

    private static void prepareWexModels(Element root) {
        if (!"actionmodels".equals(root.getTagName())) {
            throw new IllegalArgumentException("Expected an actionmodels root.");
        }
        Set<String> remaining = new HashSet<>(Set.of("header actions", "site navigation"));
        for (Node node = root.getFirstChild(); node != null;) {
            Node next = node.getNextSibling();
            if (node instanceof Element model && "model".equals(model.getTagName())
                    && Set.of("header actions", "site navigation").contains(model.getAttribute("name"))) {
                String name = model.getAttribute("name");
                Set<String> expected = new HashSet<>("header actions".equals(name)
                    ? Set.of("separator/separator", "dbcapture/startDbCapture", "dbcapture/stopDbCapture")
                    : Set.of("dbcapture/dbCaptureAdmin"));
                if (!remaining.remove(name) || !model.hasAttribute("incremental")
                        || model.getAttributes().getLength() != 2) {
                    throw new IllegalArgumentException("Review duplicate or modified model: " + name);
                }
                for (Node child = model.getFirstChild(); child != null; child = child.getNextSibling()) {
                    if (child instanceof Element action && (!"action".equals(action.getTagName())
                            || !expected.remove(action.getAttribute("type") + "/" + action.getAttribute("name")))) {
                        throw new IllegalArgumentException("Review unrelated content in model: " + name);
                    }
                    if (child instanceof Element action) {
                        for (Node detail = action.getFirstChild(); detail != null; detail = detail.getNextSibling()) {
                            if (detail instanceof Element) {
                                throw new IllegalArgumentException("Review inline action customization in model: " + name);
                            }
                        }
                        var attributes = action.getAttributes();
                        for (int index = 0; index < attributes.getLength(); index++) {
                            if (!Set.of("name", "type", "insertAfterActionName", "insertAfterObjectType")
                                    .contains(attributes.item(index).getNodeName())) {
                                throw new IllegalArgumentException("Review action attributes in model: " + name);
                            }
                        }
                    }
                }
                if (!expected.isEmpty()) {
                    throw new IllegalArgumentException("Incomplete DB Ninja model: " + name);
                }
                root.removeChild(model);
            }
            node = next;
        }
        if (!remaining.isEmpty()) {
            throw new IllegalArgumentException("Missing DB Ninja models: " + remaining);
        }
    }

    private static boolean ownedDeclaration(Element element) {
        String name = element.getAttribute("name");
        String value = element.getAttribute("value");
        if ("Property".equals(element.getTagName())) {
            if ("wt.services.service.905000".equals(name)) {
                if (!SERVICE.equals(value)) {
                    throw new IllegalArgumentException("Service slot 905000 belongs to another service.");
                }
                return true;
            }
            if (SETTINGS.contains(name)) {
                return true;
            }
            if ((name.startsWith("netmarkets.presentation.")
                    || name.startsWith("com.ptc.netmarkets.util.misc.custom")) && value.contains("DbCapture")) {
                throw new IllegalArgumentException("Review scalar/shared list property manually: " + name);
            }
        }
        if (!"AddToProperty".equals(element.getTagName()) && !"RemoveFromProperty".equals(element.getTagName())) {
            return false;
        }
        return ("com.ptc.netmarkets.util.misc.customActions".equals(name)
                    && "config/actions/DbCapture-actions.xml".equals(value))
            || ("com.ptc.netmarkets.util.misc.customActionModels".equals(name)
                    && "config/actions/DbCapture-actionModels.xml".equals(value))
            || ("netmarkets.presentation.jsFiles".equals(name)
                    && value.matches("custom/DbCapture/dbCapture(Header|Csv)(-v[0-9]+)?\\.js"))
            || ("netmarkets.presentation.cssFiles".equals(name)
                    && value.matches("custom/DbCapture/dbCapture(-v[0-9]+)?\\.css"));
    }

    private static boolean ownedReference(Element element) {
        if (!"ConfigurationRef".equals(element.getTagName())) {
            return false;
        }
        String href = element.getAttributeNS("http://www.w3.org/1999/xlink", "href").replace('\\', '/');
        return href.endsWith("/custom/DbCapture/xconf/DbCapture.service.properties.xconf")
            || "../DbCapture/xconf/DbCapture.service.properties.xconf".equals(href);
    }

    private static void mergeRole(Document document, Element root) {
        if (!"uics".equals(root.getTagName())) {
            throw new IllegalArgumentException("Expected a uics root.");
        }
        Element global = null;
        for (Node node = root.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element) {
                Element element = (Element) node;
                if ("global".equals(element.getTagName())) {
                    if (global != null) {
                        throw new IllegalArgumentException("Multiple global categories; merge manually.");
                    }
                    global = element;
                }
            }
        }
        if (global == null) {
            global = document.createElement("global");
            global.setAttribute("labelId", "globalLabel");
            global.setAttribute("incremental", "true");
            root.appendChild(global);
        }
        if (!"true".equals(global.getAttribute("incremental"))
                || !"globalLabel".equals(global.getAttribute("labelId"))) {
            throw new IllegalArgumentException("Existing global role category requires manual review.");
        }
        var components = root.getElementsByTagName("uic");
        int matches = 0;
        for (int i = 0; i < components.getLength(); i++) {
            Element component = (Element) components.item(i);
            if ("DB_CAPTURE_ADMIN".equals(component.getAttribute("name"))) {
                matches++;
                if (component.getParentNode() != global || !"false".equals(component.getAttribute("defaultAll"))) {
                    throw new IllegalArgumentException("Conflicting DB_CAPTURE_ADMIN component.");
                }
            }
        }
        if (matches > 1) {
            throw new IllegalArgumentException("Duplicate DB_CAPTURE_ADMIN components.");
        }
        if (matches == 0) {
            Element component = document.createElement("uic");
            component.setAttribute("name", "DB_CAPTURE_ADMIN");
            component.setAttribute("order", "6100");
            component.setAttribute("defaultAll", "false");
            global.appendChild(component);
        }
    }
}
