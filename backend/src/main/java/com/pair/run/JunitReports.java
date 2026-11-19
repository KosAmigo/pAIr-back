package com.pair.run;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import com.pair.run.CodeRunner.TestCase;

/**
 * JUnit XML 리포트 → 테스트 결과. 두 러너가 같이 쓴다.
 * 리포트는 응시자 코드가 돈 곳에서 나오므로 믿을 수 없는 입력이다 → DOCTYPE/외부 엔티티를 막아 XXE 를 차단한다.
 */
final class JunitReports {

    private JunitReports() {}

    static List<TestCase> parseDir(Path reports) {
        List<TestCase> out = new ArrayList<>();
        if (!Files.isDirectory(reports)) return out;
        try (Stream<Path> s = Files.list(reports)) {
            for (Path xml : s.filter(p -> p.getFileName().toString().startsWith("TEST-") && p.toString().endsWith(".xml")).toList()) {
                out.addAll(parseXml(Files.readString(xml)));
            }
        } catch (IOException e) {
            // 리포트를 못 읽으면 빈 결과로 둔다
        }
        return out;
    }

    static List<TestCase> parseXml(String xml) {
        List<TestCase> out = new ArrayList<>();
        try {
            var doc = hardenedFactory().newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
            NodeList cases = doc.getElementsByTagName("testcase");
            for (int i = 0; i < cases.getLength(); i++) {
                Element tc = (Element) cases.item(i);
                String name = tc.getAttribute("classname") + "." + tc.getAttribute("name").replace("()", "");
                Element fail = first(tc, "failure");
                if (fail == null) fail = first(tc, "error");
                if (first(tc, "skipped") != null) continue;
                String msg = fail == null ? null : fail.getAttribute("message");
                out.add(new TestCase(name, fail == null, msg));
            }
        } catch (Exception e) {
            // 깨진/악의적인 XML 은 결과에 넣지 않는다
        }
        return out;
    }

    private static DocumentBuilderFactory hardenedFactory() throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        f.setFeature("http://xml.org/sax/features/external-general-entities", false);
        f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        f.setXIncludeAware(false);
        f.setExpandEntityReferences(false);
        return f;
    }

    private static Element first(Element parent, String tag) {
        NodeList nl = parent.getElementsByTagName(tag);
        return nl.getLength() == 0 ? null : (Element) nl.item(0);
    }
}
