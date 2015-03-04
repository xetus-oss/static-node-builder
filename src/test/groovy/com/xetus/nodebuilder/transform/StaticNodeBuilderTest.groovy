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
    assertTrue detailedDiff.toString(), xmlDiff.similar()
  }

  @CompileStatic
  void testSimpleBuilder() {
    def builder = new SimpleBuilder()
    assert builder instanceof ConstrainedNodeBuilder
  }

  @CompileStatic
  void testBuilderWithSingleTag() {
    def builder = new Builder1()
    
    Node generated = builder.html()
    Node expected = new XmlParser().parseText """<html/>"""
    
    compareXml(generated, expected)
  }
  
  @CompileStatic
  void testBuilderWithSingleTagAndTextAndAttributes() {
    def builder = new Builder1()
    
    Node generated = builder.html([attr: "text"], "content")
    Node expected = new XmlParser().parseText """
    <html attr="text">content</html>
    """
    
    compareXml(generated, expected)
  }

  @CompileStatic
  void testBuilderWithNestedTags() {
    def builder = new Builder2()
    Node generated = builder.html {
      head { title() }
      body { 
        p()
        a()
      }
    }
    
    Node expected = new XmlParser().parseText """
    <html>
      <head>
        <title/>
      </head>
      <body>
        <p/>
        <a/>
      </body>
    </html>
    """
    
    compareXml(generated, expected)
  }
  
  @CompileStatic
  void testBuilderWithNestedTagsAndAttributesAndContent() {
    def builder = new Builder2()
    Node generated = builder.html {
      head { title() }
      body([onload: "function() { alert('WHOA!') }"]) { 
        p(["data-attr": "Attribute restrictions not yet implemented"], 
          "This is a paragraph!")
        a("This is a link")
      }
    }
    
    Node expected = new XmlParser().parseText """
    <html>
      <head>
        <title/>
      </head>
      <body onload="function() { alert('WHOA!') }">
        <p data-attr="Attribute restrictions not yet implemented">This is a paragraph!</p>
        <a>This is a link</a>
      </body>
    </html>
    """
    
    compareXml(generated, expected)
  } 
  
  @CompileStatic
  void testBuilderWithNestedLogic() {
    boolean showBody = false
    String text = "This is the title text!"
    def builder = new Builder2()
    Node generated = builder.html {
      
      head { title(text) }
      if (showBody) {
        body {
          p()
          a()
        }
      }
    }
    
    Node expected = new XmlParser().parseText """
    <html>
      <head>
        <title>This is the title text!</title>
      </head>
    </html>
    """
    
    compareXml(generated, expected)
  }
  
  @CompileStatic
  void testBuilderWithSpecificCapitalizationWorksAsExpected() {
    Builder3 builder = new Builder3()
    Node generated = builder.Element {
      OtherElement {
        wEiRdCapitalizAtion()
      }
    }
  }
  
  /**
   * TODO: this currently generates errors in Eclipse due
   * to a lack of compiler binding to the generated classes,
   * although it compiles fine. This is reproducible in 
   * consuming projects.
   * 
   * http://jira.codehaus.org/browse/GRECLIPSE-1198
   */
  @CompileStatic
  void testBuilderInExternalClass() {
    BuilderInExternalClass builder = new BuilderInExternalClass()
    Node generated = builder.html {
      head { title() }
      body { 
        p()
        a()
      }
    }
    
    Node expected = new XmlParser().parseText """
    <html>
      <head>
        <title/>
      </head>
      <body>
        <p/>
        <a/>
      </body>
    </html>
    """
    
    compareXml(generated, expected)
  }

  @StaticNodeBuilder
  private static class SimpleBuilder {
  }

  @StaticNodeBuilder
  private static class Builder1 {
    static schema = { html() }
  }

  @StaticNodeBuilder
  private static class Builder2 {
    static schema = {
      html {
        head { title() }
        body() {
          p()
          a()
        }
      }
    }
  }
  
  @StaticNodeBuilder
  class Builder3 {
    static schema = {
      Element {
        OtherElement {
          wEiRdCapitalizAtion()
        }
      }
    }
  }
}
