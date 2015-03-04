package com.xetus.nodebuilder.transform

@StaticNodeBuilder
class BuilderInExternalClass {
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
