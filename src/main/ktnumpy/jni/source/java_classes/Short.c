/*
 * Copyright 2019 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include "ktnumpy_includes.h"

static jmethodID newShortID = 0;

jobject java_lang_Short_new (JNIEnv *env, jshort s)
{
  if (!JNI_METHOD(newShortID, env, SHORT_TYPE, "<init>", "(S)V"))
    {
      return NULL;
    }
  return (*env)->NewObject (env, SHORT_TYPE, newShortID, s);
}