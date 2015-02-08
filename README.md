# static-node-builder

A simple project based on Cedric Champeau's staticbuilder project intended to fill the gap in XML parsing / generation where requirements are imposed that mean a schema is too strict (as is required in jaxb and other XML libraries).

An example usage is:
<pre>
MyBuilder extends ConstrainedNodeBuilder {
	schema = {
		html {
			body {
				a()
				p()
			}
     	}
   	}
}
</pre>

Which would allow consumers to build node trees using the following format: 

<pre>
MyBuilder builder = new MyBuilder()
	builder.html {
		body {
    		p("This is some text in the paragraph")
    		a([href: "http://www.link.com", data: "these are attributes"], "LINK")
		}
	}
}
</pre>