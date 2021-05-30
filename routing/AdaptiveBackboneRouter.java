package routing;

import core.Connection;
import core.DTNHost;
import core.Message;
import core.Settings;
import util.Tuple;

import java.util.*;

/**
 * Implementation of Adaptive BackBone-based router as described in
 * Adaptive Backbone-based Routing in Delay Tolerant Networks
 */
public class AdaptiveBackboneRouter extends ActiveRouter {
    /** 更新节点状态的阈值 */
    public static final double F_INIT = 2;

    /** AdaptiveBackboneRouter's setting namespace ({@value}) */
    public static final String AdaptiveBackbone_NS = "AdaptiveBackboneRouter";

    /** 连接频次 */
    public Map<DTNHost, Integer> freq;

    /** 是否被标记为骨干节点*/
    public boolean marked = true;

    /** 预运行一小段时间，使得各节点之间的相遇频次达到一定的阈值，作为生成骨干节点的信息*/
    public int warmTime = 0;

    public AdaptiveBackboneRouter(Settings s) {
        super(s);
        initFreq();
    }

    protected AdaptiveBackboneRouter(AdaptiveBackboneRouter r) {
        super(r);
        initFreq();
    }

    /**
     * 初始化频次哈希map
     */
    private void initFreq(){
        this.freq = new HashMap<DTNHost, Integer>();
        this.marked = true;
        this.warmTime = 0;
    }

    @Override
    public void changedConnection(Connection con){
        super.changedConnection(con);

        if(con.isUp()){
            DTNHost otherHost = con.getOtherNode(getHost());
            // 更新两节点之间的相遇频次
            updateMeetFreq(otherHost);
            if(warmTime > F_INIT){
                updateBackboneStatus(otherHost);
//                updateBackboneStatus2(otherHost);
            }
            warmTime++;
        }
    }

    /**
     * 更新相遇频次,相遇次数+1
     * @param host 相遇的节点
     */
    private void updateMeetFreq(DTNHost host){
        int oldFreq = getFreq(host);
        int newFreq = oldFreq + 1;
        freq.put(host, newFreq);
    }

    /**
     * 获取当前节点与相遇节点的相遇次数，没有相遇过则返回0
     * @param host 相遇节点
     * @return 当前节点与相遇节点的相遇次数
     */
    private int getFreq(DTNHost host){
        return freq.getOrDefault(host, 0);
    }

    /**
     * 更新当前节点a的骨干状态，遍历与其相连的所有节点，
     * 计算每一对邻居之间的最短路径(即相遇频次最高路径)，
     * 如果存在一对邻居最短路径包含a，那么a被置为骨干，否则a不是骨干
     * @param host 相遇节点
     */
    private void updateBackboneStatus(DTNHost host){
        boolean flag = false;
        for(Map.Entry<DTNHost, Integer> e : freq.entrySet()){
            // 如果最短路径包含当前节点，则将其置为骨干节点
            if(e.getKey().equals(host)) continue;
            List<DTNHost> path = getShortestPath(host, e.getKey());

            if(path.contains(getHost())){
                flag = true;
            }
        }
        marked = flag;
    }

    private void updateBackboneStatus2(DTNHost host){
        // 累计频次
        double AccumulatedFreq = 0;
        // 累计延迟
        double AccumulatedDelay = 0;
        // 对于本节点的任意两个邻居，它们之间的累计延迟都要小于等于包含本节点的路径的延时，才能将本节点置为非骨干节点，否则它就是骨干
        boolean flag = true;
        // 一重循环，选定本节点的非当前连接节点的邻居，即非host的邻居，通过host在2跳内找到该邻居
        for (Map.Entry<DTNHost, Integer> selfEntry : freq.entrySet()){
            AccumulatedFreq = 0;
            DTNHost h1 = selfEntry.getKey();
            if(h1.equals(host)) continue;
            MessageRouter hostRouter = host.getRouter();
            assert hostRouter instanceof AdaptiveBackboneRouter : "error";
            Map<DTNHost, Integer> hostFreq = ((AdaptiveBackboneRouter)hostRouter).freq;
            // 二重循环，选中host的任意相连节点，即第1跳节点
            for(Map.Entry<DTNHost, Integer> hostEntry : hostFreq.entrySet()){
                DTNHost h2 = hostEntry.getKey();
                if(h2.equals(getHost())) continue;
                // 选中的节点是要找的邻居，计算累积频次
                if(h2.equals(h1)){
                    AccumulatedFreq += (double) hostEntry.getValue();
                    continue;
                }
                MessageRouter r = h2.getRouter();
                assert r instanceof AdaptiveBackboneRouter : "error";
                Map<DTNHost, Integer> f = ((AdaptiveBackboneRouter)r).freq;
                // 三重循环，选中host相连节点的相连节点，即第2跳节点
                for(Map.Entry<DTNHost, Integer> e : f.entrySet()) {
                    DTNHost h3 = e.getKey();
                    if (h3.equals(getHost()) || h3.equals(host)) continue;
                    // 选中的节点是要找的邻居，计算累积频次
                    if (h3.equals(h1)) {
                        AccumulatedFreq += 1.0 / (1.0 / hostEntry.getValue() + 1.0 / e.getValue());
                    }
                }
            }
            // 计算累积延迟
            AccumulatedDelay = 1.0/AccumulatedFreq;
            // 计算本节点所在路径的延迟
            double OriginDelay = 1.0/freq.get(h1) + 1.0/freq.get(host);
            // 如果存在累积延迟大于原始延迟，则寻找失败，本节点不能被标记为非骨干
            if(AccumulatedDelay > OriginDelay){
                flag = false;
                break;
            }
        }
        marked = !flag;
    }

    /**
     * 获取两个节点之间的最短路径
     * @param from 起始节点
     * @param to 目标节点
     * @return 最短路径列表
     */
    private List<DTNHost> getShortestPath(DTNHost from, DTNHost to){
        List<DTNHost> path = new ArrayList<DTNHost>();
        // 某节点与from节点之间的距离，根据搜寻的路径动态变化
        Map<DTNHost, Double> delay = new HashMap<DTNHost, Double>();
        // 已搜寻过的节点集合
        Set<DTNHost> visited = new HashSet<DTNHost>();
        // 一个方法内部类，用于优先队列内部元素的比较，即根据该节点到from节点之间的距离比较
        class DelayComparator implements Comparator<DTNHost>{

            @Override
            public int compare(DTNHost o1, DTNHost o2) {
                double delay1 = delay.get(o1);
                double delay2 = delay.get(o2);
                if(delay1 > delay2){
                    return 1;
                }
                else if(delay1 < delay2){
                    return -1;
                }
                else {
                    return 0;
                }
            }
        }
        Queue<DTNHost> unvisited = new PriorityQueue<DTNHost>(30, new DelayComparator());
        // 搜寻过的节点及其前继节点的映射
        Map<DTNHost, DTNHost> prevHops = new HashMap<DTNHost, DTNHost>();
        /** 初始化，将from放入以搜寻过的队列，距离设为0*/
        delay.put(from, (double) 0);
        unvisited.add(from);

        if(from.equals(to)){
            return path;
        }
        DTNHost host;
        while ((host = unvisited.poll()) != null){
            // 从未搜寻的队列中取出离from最近的节点
            if(host.equals(to)){
                break;
            }

            visited.add(host);

            // 更改host的邻居到from节点的距离，并将邻居加入到unvisited队列参与寻找最短路径
            MessageRouter hostRouter = host.getRouter();
            assert hostRouter instanceof AdaptiveBackboneRouter : "自适应骨干路由只适用于相同的路由协议";
            Map<DTNHost, Integer> hostFreq = ((AdaptiveBackboneRouter)hostRouter).freq;
            // 通过host所能到达的节点
            DTNHost partialTo;
            // 根据host与from的delay计算出的host所能到达的节点与from的delay
            double delayTo;
            for(Map.Entry<DTNHost, Integer> e : hostFreq.entrySet()){
                partialTo = e.getKey();
                if(visited.contains(partialTo)){
                    continue;
                }
                delayTo = delay.get(host) + 1.0/e.getValue();
                // 如果更新后的delay小于原来其与from的delay，则更新该节点的前继节点和delay
                if(delayTo < delay.getOrDefault(partialTo, (double) 1000)){
                    prevHops.put(partialTo, host);
                    unvisited.remove(partialTo);
                    delay.put(partialTo, delayTo);
                     unvisited.add(partialTo);
                }
            }
        }
        // 根据目的节点的前继节点逐个回溯，得到路径
        if(host != null){
            DTNHost prev = prevHops.get(to);
            path.add(0, host);
            while (!prev.equals(from)){
                path.add(0, prev);
                prev = prevHops.get(prev);
            }
            path.add(0, prev);
        }
        return path;
    }

    @Override
    public void update() {
        super.update();
        if(!canStartTransfer() || isTransferring())
            return;

        // 如果消息下一跳的节点遇到的节点就是目标节点，则转发，该消息发送完成
        if(exchangeDeliverableMessages() != null)
            return;

        // 向骨干节点发送其他消息
        tryOtherMessages();
    }

    private Tuple<Message, Connection> tryOtherMessages(){
        List<Tuple<Message, Connection>> messages = new ArrayList<Tuple<Message, Connection>>();

        Collection<Message> msgCollection = getMessageCollection();

		/* 当所连接的节点是骨干节点是就发送消息 */
        for (Connection con : getConnections()) {
            DTNHost other = con.getOtherNode(getHost());
            AdaptiveBackboneRouter othRouter = (AdaptiveBackboneRouter)other.getRouter();

            if (othRouter.isTransferring()) {
                continue; // 跳过正在发送消息的节点
            }

            for (Message m : msgCollection) {
                if (othRouter.hasMessage(m.getId())) {
                    continue; // 该节点已获得该消息，不再转发
                }
                if (othRouter.marked) {
                    // 其他节点是骨干节点就转发
                    messages.add(new Tuple<Message, Connection>(m,con));
                }
            }
        }

        if (messages.size() == 0) {
            return null;
        }
        // 发送刚才生成的消息队列给对应节点
        return tryMessagesForConnected(messages);
    }


    @Override
    public MessageRouter replicate() {
        AdaptiveBackboneRouter r = new AdaptiveBackboneRouter(this);
        return r;
    }
}
