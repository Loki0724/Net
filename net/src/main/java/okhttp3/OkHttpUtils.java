/*
 * MIT License
 *
 * Copyright (c) 2023 劉強東 https://github.com/liangjingkanji
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package okhttp3;

import java.lang.reflect.Field;
import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import kotlin.jvm.JvmClassMappingKt;
import kotlin.jvm.JvmStatic;
import kotlin.reflect.KClass;
import okhttp3.internal.EmptyTags;
import okhttp3.internal.Tags;
import okhttp3.internal.cache.DiskLruCache;

@SuppressWarnings("KotlinInternalInJava")
public class OkHttpUtils {

    /**
     * 返回构造器标签的可变视图，修改会立即写回构造器。
     *
     * OkHttp 5.5 的标签已经改为不可变链表，不能再直接当作 Map 返回。
     * 视图每次访问都会读取最新链表，因此也能看到通过 Builder.tag 设置的标签。
     */
    @JvmStatic
    public static Map<Class<?>, Object> tags(Request.Builder builder) {
        return new TagMap(builder);
    }

    /**
     * 返回已构建请求的标签可变视图。
     *
     * OkHttp 的公开 API 仅支持在 Builder 上设置标签。为了兼容 Net 已有的
     * Request.tagOf(value) 和 tags() API，需要替换 Request 的 tags 字段。
     * 每次修改都通过公开 Builder.tag 创建新链表，不修改与其他请求共享的旧链表。
     * 标签应在发起请求之前设置，调用方不应在请求执行过程中并发修改标签。
     */
    @JvmStatic
    public static Map<Class<?>, Object> tags(Request request) throws NoSuchFieldException, IllegalAccessException {
        return new TagMap(request);
    }

    /**
     * 返回构造器实际使用的请求头，而不是独立副本。
     * 公开 API 无法取得 Headers.Builder，因此将这处内部访问集中在桥接层。
     */
    @JvmStatic
    public static Headers.Builder headers(Request.Builder builder) {
        return builder.getHeaders$okhttp();
    }

    @JvmStatic
    public static Headers.Builder addLenient(Headers.Builder builder, String line) {
        // 磁盘缓存需要与 OkHttp 相同的宽松解析规则，包括无名称和非 ASCII 响应头。
        return builder.addLenient$okhttp(line);
    }

    @JvmStatic
    public static DiskLruCache diskLruCache(Cache cache) {
        // 强制缓存必须与 OkHttp 共用同一个实例，避免同时打开目录导致日志或文件损坏。
        return cache.getCache$okhttp();
    }

    /**
     * 将不可变标签链表适配为 Net 历史 API 要求的可变 Map。
     *
     * 单个 Map 操作按所有者同步，避免两个视图修改同一请求时覆盖彼此的标签。
     * 迭代器使用键值快照，remove 和 Entry.setValue 则会写回所有者。
     */
    private static final class TagMap extends AbstractMap<Class<?>, Object> {
        private final Object owner;

        private TagMap(Object owner) {
            this.owner = owner;
        }

        private Tags nativeTags() {
            return owner instanceof Request.Builder
                    ? ((Request.Builder) owner).getTags$okhttp()
                    : ((Request) owner).getTags$okhttp();
        }

        @Override
        @SuppressWarnings({"rawtypes", "unchecked"})
        public Object get(Object key) {
            if (!(key instanceof Class)) return null;
            synchronized (owner) {
                return nativeTags().get(JvmClassMappingKt.getKotlinClass((Class) key));
            }
        }

        @Override
        public boolean containsKey(Object key) {
            // 原生标签没有 null 值；设置 null 与 Builder.tag(type, null) 一样表示移除。
            return get(key) != null;
        }

        @Override
        public Object put(Class<?> key, Object value) {
            if (key == null) throw new NullPointerException("tag type == null");
            synchronized (owner) {
                Object previous = get(key);
                setTag(key, value);
                return previous;
            }
        }

        @Override
        public Object remove(Object key) {
            if (!(key instanceof Class)) return null;
            return put((Class<?>) key, null);
        }

        @Override
        public void clear() {
            synchronized (owner) {
                for (Class<?> key : snapshot().keySet()) {
                    setTag(key, null);
                }
            }
        }

        @SuppressWarnings({"rawtypes", "unchecked"})
        private void setTag(Class<?> key, Object value) {
            if (owner instanceof Request.Builder) {
                ((Request.Builder) owner).tag((Class) key, value);
                return;
            }
            Request request = (Request) owner;
            Tags replacement = request.newBuilder().tag((Class) key, value).getTags$okhttp();
            try {
                TagFields.REQUEST_TAGS.set(request, replacement);
            } catch (IllegalAccessException e) {
                throw incompatibleTags(e);
            }
        }

        private Map<Class<?>, Object> snapshot() {
            synchronized (owner) {
                Tags tags = nativeTags();
                if (tags instanceof EmptyTags) return Collections.emptyMap();
                ArrayList<Map.Entry<Class<?>, Object>> entries = new ArrayList<>();
                try {
                    while (TagFields.LINKED_TAGS.isInstance(tags)) {
                        KClass<?> key = (KClass<?>) TagFields.KEY.get(tags);
                        entries.add(new SimpleImmutableEntry<>(
                                JvmClassMappingKt.getJavaClass(key), TagFields.VALUE.get(tags)));
                        tags = (Tags) TagFields.NEXT.get(tags);
                    }
                    if (!(tags instanceof EmptyTags)) {
                        throw incompatibleTags(null);
                    }
                } catch (IllegalAccessException e) {
                    throw incompatibleTags(e);
                }
                // 原生链表按最新标签在前存储，Map 则按原来的插入顺序遍历。
                Collections.reverse(entries);
                Map<Class<?>, Object> result = new LinkedHashMap<>();
                for (Map.Entry<Class<?>, Object> entry : entries) {
                    result.put(entry.getKey(), entry.getValue());
                }
                return result;
            }
        }

        @Override
        public Set<Entry<Class<?>, Object>> entrySet() {
            return new AbstractSet<Entry<Class<?>, Object>>() {
                @Override
                public int size() {
                    return snapshot().size();
                }

                @Override
                public void clear() {
                    TagMap.this.clear();
                }

                @Override
                public Iterator<Entry<Class<?>, Object>> iterator() {
                    final Iterator<Entry<Class<?>, Object>> iterator = snapshot().entrySet().iterator();
                    return new Iterator<Entry<Class<?>, Object>>() {
                        private Class<?> currentKey;

                        @Override
                        public boolean hasNext() {
                            return iterator.hasNext();
                        }

                        @Override
                        public Entry<Class<?>, Object> next() {
                            Entry<Class<?>, Object> entry = iterator.next();
                            currentKey = entry.getKey();
                            return new SimpleEntry<Class<?>, Object>(entry) {
                                @Override
                                public Object setValue(Object value) {
                                    Object previous = TagMap.this.put(getKey(), value);
                                    super.setValue(value);
                                    return previous;
                                }
                            };
                        }

                        @Override
                        public void remove() {
                            // 先让快照迭代器检查 next/remove 调用顺序，再写回实际标签。
                            iterator.remove();
                            TagMap.this.remove(currentKey);
                        }
                    };
                }
            };
        }
    }

    /**
     * 公开 API 无法枚举标签，只对 OkHttp 5.5 的三个链表字段做受控反射。
     * consumer-rules.pro 精确保留这些字段，避免 R8 重命名、合并或常量化。
     * 元数据延迟初始化，让正常的请求头与缓存桥接不承担反射初始化成本。
     */
    private static final class TagFields {
        private static final Class<?> LINKED_TAGS;
        private static final Field REQUEST_TAGS;
        private static final Field KEY;
        private static final Field VALUE;
        private static final Field NEXT;

        static {
            try {
                LINKED_TAGS = Class.forName("okhttp3.internal.LinkedTags");
                REQUEST_TAGS = accessibleField(Request.class, "tags");
                KEY = accessibleField(LINKED_TAGS, "key");
                VALUE = accessibleField(LINKED_TAGS, "value");
                NEXT = accessibleField(LINKED_TAGS, "next");
            } catch (ReflectiveOperationException e) {
                throw incompatibleTags(e);
            }
        }

        private static Field accessibleField(Class<?> type, String name) throws NoSuchFieldException {
            Field field = type.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        }
    }

    private static IllegalStateException incompatibleTags(Exception cause) {
        return new IllegalStateException("Unsupported OkHttp tag storage. Net requires OkHttp 5.5.x "
                + "and its consumer ProGuard rules for mutable request tags.", cause);
    }
}
