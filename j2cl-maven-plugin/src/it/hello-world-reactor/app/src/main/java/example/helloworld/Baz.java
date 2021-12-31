/*
 * Copyright 2015 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package example.helloworld;

import jsinterop.annotations.JsMethod;
import jsinterop.annotations.JsType;
import example.helloworldlib.Foo1;
import example.helloworldlib.Foo2;
import example.helloworldlib.Base1;
import example.helloworldlib.Base2;
import example.helloworld.Base3;
import example.helloworldlib.I1;

/**
 * A simple hello world example.
 *
 * <p>Note that it is marked as @JsType as we would like to call have whole class available to use
 * from JavaScript.
 */
@JsType
public class Baz extends Base3 implements I1 {

    @JsType
    public static class InnerBar extends Base2 {
        public InnerBar() {
            super();
        }

        @JsType
        public static class InnerInnerBar {
            public InnerInnerBar() {
                super();
            }

            public String getMessage() {
                return new Foo2().getMessage();
            }
        }
    }
}
