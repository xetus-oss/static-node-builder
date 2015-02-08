package com.xetus.nodebuilder.transform

import org.custommonkey.xmlunit.DetailedDiff
import org.custommonkey.xmlunit.Diff

import com.xetus.nodebuilder.runtime.ConstrainedNodeBuilder

import groovy.transform.CompileStatic
import groovy.util.GroovyTestCase
import groovy.xml.XmlUtil

class StaticNodeBuilderTest extends GroovyTestCase {

  private static void compareXml(Node generated, Node expected) {
    def xmlDiff = new Diff(
        XmlUtil.serialize(generated), XmlUtil.serialize(expected))
    System.out.println "== Generated XML == \n"+
        XmlUtil.serialize(generated) + "\n"

    def detailedDiff = new DetailedDiff(xmlDiff)
    assertTrue detailedDiff.toString() , xmlDiff.similar()
  }

  @CompileStatic
  void testSimpleBuilder() {
    def builder = new SimpleBuilder()
    assert builder instanceof ConstrainedNodeBuilder
  }

  @CompileStatic
  void testBuilder1() {
    def builder = new Builder1()
    Node generated = builder.html()
    Node expected = new XmlParser().parseText """<html/>"""
    compareXml(generated, expected)
  }

  
  @CompileStatic
  void testBuilder2() {
    def builder = new Builder2()
    Node generated = builder.html {
      head { title("I'm a cool title! Wowee!") }
      body([onload: "function() { alert('WHOA!') }"]) { 
        p([style: "Attribute restrictions not yet implemented"], "This is a paragraph!")
        a("This is a link")
      }
    }
    
    Node expected = new XmlParser().parseText """
    <html>
      <head>
        <title/>
      </head>
      <body onload="function() { alert('WHOA!') }">
        <p style="Attribute restrictions not yet implemented">This is a paragraph!</p>
        <a>This is a link</a>
      </body>
    </html>
    """
    compareXml(generated, expected)
  } 

  @StaticNodeBuilder
  private class SimpleBuilder {
  }

  @StaticNodeBuilder
  private class Builder1 {
    static schema = { html() }
  }

  @StaticNodeBuilder
  private class Builder2 {
    static schema = {
      html {
        head { title() }
        body(["onload"]) {
          p()
          a(["href", "target"])
        }
      }
    }
  } 
}
