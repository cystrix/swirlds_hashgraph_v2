package com.cystrix.hashgraph.hashview;

import com.alibaba.fastjson2.JSON;
import com.cystrix.hashgraph.exception.BusinessException;
import com.cystrix.hashgraph.hashview.search.DFS;
import com.cystrix.hashgraph.util.SHA256;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Data
public class HashgraphMember {
    {
        Map<String, String> keyPair = SHA256.generateKeyPairBase64();
        PK = keyPair.get("PK");
        SK = keyPair.get("SK");
    }
    private Integer id;
    private  String name;
    private ConcurrentHashMap<Integer, List<Event>> hashgraph; // 以map的方式保留hashgraph 副本
    private int numNodes; // 成员节点总数
    private boolean shutdown = false;
    private  final String PK;
    private  final String SK; // sk base64 encode
    private List<Transaction> waitForPackEventList;
    private ConcurrentHashMap<Integer, List<Event>> witnessMap; // 存放每轮的见证者
    private ConcurrentHashMap<String, Event> eventHashMap;

    private Integer maxRound = 0;
    private int coinRound = 10;
    private Object lock = new Object();

    public String getPk() {
        return this.PK;
    }
    public String getSk() {
        return this.SK;
    }
    public HashgraphMember(Integer id, String name, int numNodes) {
        this.id = id;
        this.name = name;
        this.numNodes = numNodes;
        this.hashgraph = new ConcurrentHashMap<>();
        this.eventHashMap = new ConcurrentHashMap<>();
        this.witnessMap = new ConcurrentHashMap<>();
        this.waitForPackEventList = new ArrayList<>();
        for (int i = 0; i < this.numNodes; i++) {
            List<Event> chain = new ArrayList<>();
            this.hashgraph.put(i,chain);
        }
        // 初始化第一个事件
        Event e = new Event();
        e.setTimestamp(System.currentTimeMillis());
        e.setNodeId(id);
        e.setSignature(SHA256.signEvent(e, getSk()));

        this.hashgraph.get(id).add(e);
        String eventHash = null;
        try {
            eventHash = SHA256.sha256HexString(JSON.toJSONString(e));
            this.eventHashMap.put(eventHash, e);
        }catch (Exception ex) {
            throw new BusinessException(ex);
        }
    }

    @Deprecated
    public synchronized boolean addEvent(Event event) {
        List<Event> events = hashgraph.get(event.getNodeId());
        if (events != null) {
            if (events.size() != 0) {
                Event lastParentEvent = events.get(events.size()-1);
                if (lastParentEvent != event.getSelfParent()) {
                    return false;
                }
                Event otherParentEvent = event.getOtherParent();
                Integer nodeId = otherParentEvent.getNodeId();
                int otherChainSize = hashgraph.get(nodeId).size();
                if (hashgraph.get(nodeId).get(otherChainSize - 1) != otherParentEvent) {
                    return false;
                }
                events.add(event);
            }else {
                events.add(event);
            }
        }
        return true;
    }

    // todo 没有考虑拜占庭节点行为
    public synchronized boolean addEventBatch(HashMap<Integer, List<Event>> subHashgraph) {
        this.hashgraph.forEach((id, chain)->{
            if (subHashgraph.containsKey(id)) {
                List<Event> subChain = subHashgraph.get(id);
                chain.addAll(subChain);
                for (Event e: subChain) {
                    try {
                        this.eventHashMap.put(SHA256.sha256HexString(JSON.toJSONString(e)), e);
                    }catch (Exception ex) {
                        ex.printStackTrace();
                    }
                }
            }
        });

        // for build DFS structure
        subHashgraph.forEach((id, chain)->{
            // 找到新加入的事件的自父亲event
            if (chain.size() != 0) {
                for (int i = chain.size()- 1; i > 0; i--) {
                    Event e = chain.get(i);
                    e.setSelfParent(chain.get(i-1));
                    Event otherParent = this.eventHashMap.get(e.getOtherParentHash());
                    e.setOtherParent(otherParent);

                    List<Event> neighbors = new ArrayList<>(2);
                    neighbors.add(e.getSelfParent());
                    neighbors.add(otherParent);
                    e.setNeighbors(neighbors);
                }

                // 若不是第一个event，则说明有父亲事件
                Event e = chain.get(0);
                int index = this.hashgraph.get(id).indexOf(e);
                if (index != 0) {
                    List<Event> neighbors = new ArrayList<>(2);
                    Event selfParent = this.hashgraph.get(id).get(index-1);
                    Event otherParent = this.eventHashMap.get(e.getOtherParentHash());
                    e.setSelfParent(selfParent);
                    e.setOtherParent(otherParent);
                    neighbors.add(selfParent);
                    neighbors.add(otherParent);
                    e.setNeighbors(neighbors);
                }
            }
        });
        return true;
    }


    public void divideRounds() {
        this.witnessMap.clear();

        AtomicInteger maxLen = new AtomicInteger(Integer.MIN_VALUE);
        List<Integer> chainSizeList = new ArrayList<>(this.numNodes);
        // 获得当前最长链长度，并记录每条链的长度
        this.getHashgraph().forEach((id, chain)->{
            maxLen.set(Math.max(chain.size(), maxLen.get()));
            chainSizeList.add(chain.size());
        });
        // 层次遍历hashgraph，确定每个事件的轮次
        for (int i = 0; i < maxLen.get(); i++) {
            for (int j = 0; j < this.numNodes; j++) {
                // 若该坐标存在事件，则判断其轮次
                if (chainSizeList.get(j) > i) {
                    Event e =  this.hashgraph.get(j).get(i);
                    Integer r;
                    if (i == 0) {
                        e.setIsWitness(true);
                        e.setCreatedRound(1);
                        if (!this.witnessMap.containsKey(1)) {
                            this.witnessMap.put(1, new ArrayList<>());
                        }
                        this.witnessMap.get(1).add(e);
                    }else {
                        Event selfParentEvent = e.getSelfParent();
                        r = selfParentEvent.getCreatedRound();
                        if (r == null) {
                            throw new BusinessException("自父亲事件的创建轮次不能为空");
                        }
                        AtomicInteger vote = new AtomicInteger(0);

                        this.witnessMap.get(r).forEach(witness->{
                            if (isStronglySee(e, witness)) {
                                vote.getAndIncrement();
                            }
                        });

                        if (vote.get() > (2 * numNodes / 3)) {
                            e.setIsWitness(true);
                            e.setCreatedRound(r+1);
                            maxRound = Math.max(maxRound, r + 1);
                            if (!witnessMap.containsKey(r + 1)) {
                                witnessMap.put(r+1, new ArrayList<>());
                            }
                            witnessMap.get(r+1).add(e);
                        }else {
                            e.setIsWitness(false);
                            e.setCreatedRound(r);
                        }
                    }

                }
            }
        }
       /* System.out.println("******************************************************************************************");
        System.out.println("node_id：" + this.getId() + " 的witness副本");
        this.witnessMap.forEach((round, witnessList)->{
            System.out.print(round+" ");
            System.out.print(witnessList);
            System.out.println();
        });
        System.out.println("******************************************************************************************\n");*/
    }

    public void divideRounds2() {

    }

    public void decideFame() {
        // 遍历每轮的见证人
        this.witnessMap.forEach((r, witnessList)->{
            // 该见证人之后存在两轮才能被投票和计票
            if (this.witnessMap.containsKey(r+2)) {
                AtomicInteger voteYes = new AtomicInteger();
                AtomicInteger voteNo = new AtomicInteger();
                List<Event> voteEventList = this.witnessMap.get(r+1);       // 投票轮见证人
                List<Event> countVoteEventList = this.witnessMap.get(r+2);  // 计票轮见证人

                // 对候选轮的见证人 进行虚拟投票和计票
                for (Event event : witnessList) {
                    // 投票轮见证人 对 候选轮的某个候选见证人投票，并由计票轮见证人 计票
                    for (int i = 0; i < countVoteEventList.size(); i++) {
                        if (voteEventList.get(0).getCreatedRound() % coinRound != 0) {
                            for (int k = 0; k < voteEventList.size(); k++) {
                                if (isSee(voteEventList.get(k), event) && isStronglySee(countVoteEventList.get(i), voteEventList.get(k))) {
                                    voteYes.incrementAndGet();
                                }
                                try {
                                    if (!isSee(voteEventList.get(k), event) && isStronglySee(countVoteEventList.get(i), voteEventList.get(k))) {
                                        voteNo.incrementAndGet();
                                    }
                                }catch (Exception e) {
                                    e.printStackTrace();
                                    throw new BusinessException(e.getMessage());
                                }
                            }
                            // 超过2/3的见证人都强可见该事件，则该见证人著名
                            if (voteYes.get() > (2 * this.numNodes / 3)) {
                                event.setIsFamous(true);
                                break;
                                // 超过2/3的见证人都不强可见该事件，则该见证人不著名
                            }else if (voteNo.get() > (2 * this.numNodes / 3)) {
                                event.setIsFamous(false);
                                break;
                            }
                        }

                    }

                    if (event.getIsFamous() == null) {
                        // 在r的后两轮无法确定 见证人的声望
                        // 在r之后的第一个coin round 进行投票， 然后在 coin round后一轮进行选举
                        // cr = 15  coinRound = 20  =  cr / 10 * 10
                        while (true) {
                            int createdRound = event.getCreatedRound();
                            int coinRound;
                            if (createdRound % 10 == 0) {
                                coinRound = createdRound + 10;
                            }else {
                                coinRound = createdRound / 10 * 10 + 10;
                            }
                            if (maxRound > coinRound) {

                            }else {
                                break;
                            }
                        }

                    }

                }
            }
        });

    }

    public void decideFame2() {
        // 遍历每轮的见证人
       List<Event> eventList = new ArrayList<>();
        for (List<Event> events: this.getWitnessMap().values()) {
           for (Event e: events) {
               eventList.add(e);
           }
       }
       for(int i = 0; i < eventList.size(); i ++) {
           for(int j = 0; j < eventList.size(); j ++) {
               Event x = eventList.get(i);
               Event y = eventList.get(j);
               // if x.witness and y.witness and y.round > x.round
               if (x.getCreatedRound() < y.getCreatedRound()) {
                   // d <- y.round - x.round
                   int d = y.getCreatedRound() - x.getCreatedRound();

                   // s <- the set of witness events in round y.round - 1 that y can strongly see
                   List<Event>  witness = filterWitnessList(y, this.witnessMap.get(y.getCreatedRound()-1));

                   // v <- majority vote in s  (is TRUE for a tie)
                   // t <- number of events in s with a vote of v
                   AtomicInteger t = new AtomicInteger();
                   boolean v = getMajorityVote(witness, t);

                   if (d == 1) {
                       // y.vote <- can y see x ?
                       y.setVoteRes(isSee(y, x));
                   }else {
                       if ( d % coinRound > 0) {
                          if (t.get() > (2 * this.numNodes / 3)) {
                              x.setIsFamous(v);
                              y.setVoteRes(v);
                              break;
                          }
                       }else {
                           if (t.get() > (2 * this.numNodes / 3)) {
                               y.setVoteRes(v);
                           }else {
                               y.setVoteRes(voteBySignature(y.getSignature()));
                           }
                       }
                   }
               }

           }
       }
       /* System.out.println("======================================================================================");
        System.out.println(this.witnessMap);*/
    }

    private List<Event> filterWitnessList(Event y, List<Event> witnessList) {
        List<Event> targetWitnessList = new ArrayList<>();
        for (int i = 0; i < witnessList.size(); i ++) {
            Event witness = witnessList.get(i);
            if (isStronglySee(y, witness)) {
                targetWitnessList.add(witness);
            }
        }
        return  targetWitnessList;
    }

    private boolean voteBySignature(String signature) {
        byte[] sign = Base64.getDecoder().decode(signature);
        int len = sign.length;
        int i = sign[len / 2] & 0b00001000;
        return i != 0;
    }


    // v <- majority vote in s (is TRUE for a tie)
    private boolean getMajoritySuggestion(Event x, List<Event> witnessList, AtomicInteger voteNum) {
        AtomicInteger voteYes  = new AtomicInteger(0);
        AtomicInteger voteNo  = new AtomicInteger(0);

        witnessList.forEach(witness->{
            if (isSee(witness, x)) {
                voteYes.getAndIncrement();
            }else {
                voteNo.getAndIncrement();
            }
        });
        if (voteYes.get() >= voteNo.get()) {
            voteNum.set(voteYes.get());
            return true;
        }else {
            voteNum.set(voteNo.get());
            return false;
        }
    }


    private boolean getMajorityVote(List<Event> witnessList, AtomicInteger voteNum) {
        int voteYes = 0;
        int voteNo = 0;
        for (Event e : witnessList) {
            if (e.isVoteRes()) {
                voteYes += 1;
            }else {
                voteNo += 1;
            }
        }
        if ( voteYes >= voteNo) {
            voteNum.set(voteYes);
            return true;
        }else {
            voteNum.set(voteNo);
            return false;
        }
    }


    public void findOrder() {

    }


    private boolean isSee(Event src, Event dest) {
        if (src == dest) {
            return true;
        }
        cleanVisited();
        return DFS.findPath(src, dest, new ArrayList<>());
    }

    private boolean isStronglySee(Event src, Event dest) {
        if (src == dest) {
            return true;
        }
        cleanVisited();
        Set<Integer> set = new HashSet<>();
        List<List<Event>> allPaths = DFS.findAllPaths(src, dest);
        for (List<Event>  path: allPaths) {
            for (Event e: path) {
                set.add(e.getNodeId());
            }
            if (set.size() > (2 * numNodes / 3)) {
                return true;
            }else {
                set.clear();
            }
        }
        return false;
    }

    private void cleanVisited() {
        this.hashgraph.forEach((id, chain)-> chain.forEach(e-> e.setVisited(false)));
    }
}
