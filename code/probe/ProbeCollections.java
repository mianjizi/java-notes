import java.util.*;

/** 集合探针：接口与实现分离、迭代器、不可变工厂、HashMap 的真实行为 */
public class ProbeCollections {
    public static void main(String[] args) {
        System.out.println("--- 同一条声明，换实现类改变的是性能不是语义 ---");
        List<String> a = new ArrayList<>(List.of("c", "a", "b"));
        List<String> l = new LinkedList<>(List.of("c", "a", "b"));
        System.out.println("ArrayList   = " + a.getClass().getName());
        System.out.println("LinkedList  = " + l.getClass().getName());
        System.out.println("链表随机访问 get(1) 很慢（要走到第 1 个节点）");

        System.out.println("--- 遍历时修改集合：fail-fast 只在\"下一次 next()\"时才检查 ---");
        try {
            var x = new ArrayList<>(List.of("a", "b", "c"));
            for (String s : x) if (s.equals("a")) x.remove(s);
            System.out.println("删开头元素 -> 没抛异常，x = " + x);
        } catch (ConcurrentModificationException e) {
            System.out.println("删开头元素 -> ConcurrentModificationException（下次 next() 比对 modCount 发现）");
        }
        try {
            var y = new ArrayList<>(List.of("a", "b", "c"));
            for (String s : y) if (s.equals("b")) y.remove(s);
            System.out.println("删倒数第二个 -> 没抛异常，y = " + y + "  ← 循环恰好结束，检查根本没执行（fail-fast 会漏检）");
        } catch (ConcurrentModificationException e) {
            System.out.println("删倒数第二个 -> ConcurrentModificationException");
        }
        System.out.println("正确写法 iterator.remove()：" + removeViaIterator(a) + " 集合 = " + a);
        System.out.println("或 removeIf：" + removeIfVersion(new ArrayList<>(List.of("c", "a", "b"))));

        System.out.println("--- List.of / Map.of 是不可变的，不是\"可改的只读视图\" ---");
        try { List.of("x").add("y"); }
        catch (UnsupportedOperationException e) { System.out.println("List.of(...).add -> UnsupportedOperationException"); }

        System.out.println("--- HashMap：无序不是\"随便\"，是哈希与容量决定 ---");
        Map<String, Integer> m = new HashMap<>();
        m.put("apple", 1); m.put("banana", 2); m.put("cherry", 3);
        System.out.println("HashMap 遍历 = " + m.keySet());
        System.out.println("TreeMap 遍历 = " + new TreeMap<>(m).keySet() + "（按键排序）");
        System.out.println("LinkedHashMap = " + new LinkedHashMap<>(m).keySet() + "（按插入顺序）");

        System.out.println("--- hashCode 全冲突时会发生什么（只会变慢，不会算错）---");
        Map<Collide, Integer> cm = new HashMap<>();
        long t0 = System.nanoTime();
        for (int i = 0; i < 20_000; i++) cm.put(new Collide(i), i);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        System.out.printf("放 20000 个 hashCode 全相同的键：size=%d，耗时 %d ms（正常应是毫秒级以下）%n", cm.size(), ms);

        System.out.println("--- 泛型：编译期检查 + 类型擦除 ---");
        List<String> strings = new ArrayList<>();
        strings.add("only strings");
        System.out.println("List<String>.getClass() = " + strings.getClass().getName() + "（运行时看不到 <String>）");
        System.out.println("List<String> 与 List<Integer> 的 getClass() 相等 = "
                + (strings.getClass() == new ArrayList<Integer>().getClass()));

        System.out.println("--- 常用收尾：Map.merge / computeIfAbsent ---");
        Map<String, List<Integer>> idx = new HashMap<>();
        idx.computeIfAbsent("奇数", k -> new ArrayList<>()).add(1);
        idx.computeIfAbsent("奇数", k -> new ArrayList<>()).add(3);
        System.out.println("computeIfAbsent 累加 = " + idx);
        System.out.println("getOrDefault = " + m.getOrDefault("durian", -1));
    }

    static List<String> removeViaIterator(List<String> list) {
        for (var it = list.iterator(); it.hasNext(); ) if (it.next().equals("a")) it.remove();
        return list;
    }

    static List<String> removeIfVersion(List<String> list) {
        list.removeIf(s -> s.equals("a"));
        return list;
    }

    static class Collide {
        final int v;
        Collide(int v) { this.v = v; }
        @Override public int hashCode() { return 42; }        // 故意全冲突
        @Override public boolean equals(Object o) { return o instanceof Collide c && c.v == v; }
    }
}
