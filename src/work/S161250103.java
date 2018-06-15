package work;

import bottom.BottomMonitor;
import bottom.BottomService;
import bottom.Task;
import main.Schedule;

import java.io.IOException;

/**
 *
 * 注意：请将此类名改为 S+你的学号   eg: S161250001
 * 提交时只用提交此类和说明文档
 *
 * 在实现过程中不得声明新的存储空间（不得使用new关键字，反射和java集合类）
 * 所有新声明的类变量必须为final类型
 * 不得创建新的辅助类
 *
 * 可以生成局部变量
 * 可以实现新的私有函数
 *
 * 可用接口说明:
 *
 * 获得当前的时间片
 * int getTimeTick()
 *
 * 获得cpu数目
 * int getCpuNumber()
 *
 * 对自由内存的读操作  offset 为索引偏移量， 返回位置为offset中存储的byte值
 * byte readFreeMemory(int offset)
 *
 * 对自由内存的写操作  offset 为索引偏移量， 将x写入位置为offset的内存中
 * void writeFreeMemory(int offset, byte x)
 *
 */
public class S161250103 extends Schedule{

    //这个地址中存了PCB的数量
    private static final int PCB_NUM=0;

    //CPU状态数组的起始地址   [4,260)  最多64个CPU      记录上个时刻每个CPU运行的Task
    private static final int CPU_STATE_BASE=4;

    //[260,324)表示对应的CPU是否已经分配   1byte/1CPU
    private static final int CPU_BITMAP=260;

    //PCB table      [512,9*512)   存每一个PCB的首地址
    private static final int PCB_TABLE_BASE=512;

    //资源位示图起始地址   [9*512,9*512+128)          1byte/1resource
    private static final int RESOURCE_STATE_BASE=9*512;

    //可以使用的动态内存    [10*512,20*1024)
    private static final int DYNAMIC_MEMORY_BASE=10*512;

    private static final int MEMORY_LIMIT=20*1024;

    @Override
    public void ProcessSchedule(Task[] arrivedTask, int[] cpuOperate) {
        
        int cpuNum = getCpuNumber();
        //save task first
        if (arrivedTask != null) {
            for (Task task : arrivedTask) {
                saveTask(task);
            }
        }

        //schedule        shortest remaining time first      considering the waiting time
        cleanResource();
        int remain_cpu = cpuNum;
        int tid=0;
        while (remain_cpu > 0&&(tid = getTask2Run()) != 0 ) {    //为什么要先判断cpu是否用完？否则会缩减某个task的时间片！！！
            remain_cpu--;
            boolean flag=false;
            for (int i = 0; i < cpuNum; i++) {
                if (readInt(CPU_STATE_BASE + 4 * i) == tid && readFreeMemory(CPU_BITMAP + i) == 0) {
                    //上一时刻该CPU也在运行这个task
                    writeFreeMemory(CPU_BITMAP + i, (byte) 1);
                    cpuOperate[i] = tid;
                    flag = true;
                    break;
                }
            }

            if (!flag) {
                //上一时刻这个task没有被run
                for (int i = 0; i < cpuNum; i++) {
                    if (readFreeMemory(CPU_BITMAP + i) == 0) {
                        writeFreeMemory(CPU_BITMAP + i, (byte) 1);
                        cpuOperate[i]=tid;
                        break;
                    }
                }
            }
        }
        cpuNum = getCpuNumber();
        //更新CPU_STATE
        for (int i = 0; i < cpuNum; i++) {
            writeInt(CPU_STATE_BASE + 4 * i, cpuOperate[i]);
        }
        //更新wait time
        int taskNum = readInt(PCB_NUM);
        for (int i = 0; i < taskNum; i++) {
            int addr = readInt(PCB_TABLE_BASE + 4 * i);
            int id = readInt(addr + 4);
            if (!inRunningList(id, cpuOperate)) {
                writeInt(addr + 12, readInt(addr + 12) + 1);
            }
        }
        
    }

    private boolean inRunningList(int tid, int[] list) {
        boolean flag=false;
        for (int i : list) {
            if (i == tid) {
                flag = true;
                break;
            }
        }
        return flag;
    }

    private void cleanResource(){
        //clear resource bitmap
        for (int i = 0; i < 128; i++) {
            writeFreeMemory(RESOURCE_STATE_BASE + i, (byte) 0);
        }
        //clear cpu bitmap
        int cpuNum = getCpuNumber();
        for (int i = 0; i < cpuNum; i++) {
            writeFreeMemory(CPU_BITMAP + i, (byte) 0);
        }
    }

    private int getTask2Run(){
        int tid=0,pcbid=-1;          //pcbid 在PCB table中的index
        int taskNum = readInt(PCB_NUM);
        double max_score=0.0;

        for (int i = 0; i < taskNum; i++) {
            int addr = readInt(PCB_TABLE_BASE + 4 * i);

            if (addr < 5120) {
                throw new RuntimeException("addr error!!!");
            }
            int remainTime = readInt(addr + 8);

            if (remainTime > 0) {
                //task not finished yet
                //check if the resources are available
                int size = readInt(addr);
                boolean available = true;

                for (int j = 0; j < size - 3; j++) {
                    int resourceid = readInt(addr + 16 + 4 * j)-1;
                    if (readFreeMemory(RESOURCE_STATE_BASE + resourceid) != 0) {
                        available = false;
                        break;
                    }
                }

                if (available) {
                    int waitTime = readInt(addr + 12);
                    double temp = calculateTaskScore(remainTime, waitTime);
                    if (temp > max_score) {
                        max_score = temp;
                        pcbid = i;
                    }
                }
            }
        }

        //now the pcbid th task is selected
        if (pcbid != -1) {
            int addr = readInt(PCB_TABLE_BASE + 4 * pcbid);
            tid = readInt(addr + 4);
            int size = readInt(addr);

            //mark the resources
            for (int i = 0; i < size - 3; i++) {
                int resourceid = readInt(addr + 16 + 4 * i)-1;
                writeFreeMemory(RESOURCE_STATE_BASE + resourceid, (byte) 1);
            }

            //reduce the remain time
            int remainTime = readInt(addr + 8) - 1;
            writeInt(addr + 8, remainTime);

            if (remainTime == 0) {
                //task finished
                //remove it
                clear_pcb(pcbid);
            }
        }

        return tid;
    }

    /**
     * 计算tid对应的task在调度中的权重
     * @param remainTime
     * @param waitTime
     * @return
     */
    private double calculateTaskScore(int remainTime,int waitTime) {
        double score = 1024.0 / ((double) remainTime);
        score += waitTime < 5 ? 1.0 :
                waitTime < 15 ? (2.0 * waitTime + 1.0) : (double) (pow(2, waitTime - 11));
        return score;
    }

    /**
     * 分配内存
     * @param bytes
     * @return
     */
    private int allocateMemory(int bytes) {
        int pos = -1;
        for (int i = DYNAMIC_MEMORY_BASE; i+bytes-1 < MEMORY_LIMIT; i++) {
            boolean flag=true;
            for (int j = 0; j < bytes; j++) {
                if (readFreeMemory(i + j) != 0) {
                    flag=false;
                    break;
                }
            }
            if (flag) {
                pos = i;
                break;
            }
        }
        return pos;
    }

    /**
     * PCB在内存中保存的形式：
     * ---------------------------------
     * |size : num of the following int|
     * ---------------------------------
     * |             tid               |
     * ---------------------------------
     * |         remain    time        |
     * ---------------------------------
     * |          wait   time          |
     * ---------------------------------
     * |         int[] resource        |
     * ---------------------------------
     * @param task
     */
    private void saveTask(Task task) {
        //先算出这一个PCB对应的起始地址
        int size = 3 + task.resource.length;
        int pcbNum = readInt(PCB_NUM);
        int start = allocateMemory(4 * (size + 1));
        if (start == -1) {
            throw new RuntimeException("allocate memory in saving task failed!");
        }
        //在PCB table中记录当前项的地址
        writeInt(4 * pcbNum + PCB_TABLE_BASE, start);
        pcbNum++;
        writeInt(PCB_NUM, pcbNum);

//        System.out.println("pcb table base = " + PCB_TABLE_BASE);
//        System.out.println("pcb num = " + pcbNum);
//        System.out.println("saving task " + task.tid + " at addr " + start + " pcb addr is " + (PCB_TABLE_BASE + 4 * pcbNum-4));
//        System.out.println();
        //save PCB
        writeInt(start, size);
        start += 4;
        writeInt(start, task.tid);
        start+=4;
        writeInt(start, task.cpuTime);             //remain cpu time
        start+=4;
        writeInt(start, 0);     //wait cpu time
        start+=4;
        for (int i : task.resource) {
            writeInt(start, i);
            start+=4;
        }

    }

    /**
     * 删除第pcbid项pcb table项
     * @param pcbid
     */
    private void clear_pcb(int pcbid){

//        System.out.println("cleaning pcb : " + pcbid);

        int start = readInt(PCB_TABLE_BASE + 4 * pcbid);
        int size = readInt(start);

        for (int i = 0; i < (size + 1) * 4; i++) {
            writeFreeMemory(i + start, (byte) 0);
        }

        int pcbNum = readInt(PCB_NUM);
        pcbNum--;
        if (pcbid != pcbNum) {
            writeInt(pcbid * 4 + PCB_TABLE_BASE, readInt(pcbNum * 4 + PCB_TABLE_BASE));
        }
        writeInt(PCB_NUM, pcbNum);
    }

    private void writeInt(int beginIndex, int value) {
        writeFreeMemory(beginIndex+3, (byte) ((value&0x000000ff)));
        writeFreeMemory(beginIndex+2, (byte) ((value&0x0000ff00)>>8));
        writeFreeMemory(beginIndex+1, (byte) ((value&0x00ff0000)>>16));
        writeFreeMemory(beginIndex, (byte) ((value&0xff000000)>>24));
    }

    private int readInt(int beginIndex) {
        int ans = 0;
        ans += (readFreeMemory(beginIndex) & 0xff) << 24;
        ans += (readFreeMemory(beginIndex + 1) & 0xff) << 16;
        ans += (readFreeMemory(beginIndex + 2) & 0xff) << 8;
        ans += (readFreeMemory(beginIndex + 3) & 0xff);
        return ans;
    }

    private int pow(int x, int n) {
        int res=1;
        for (int i = 0; i < n; i++) {
            res *= x;
        }
        return res;
    }
    /**
     * 执行主函数 用于debug
     * 里面的内容可随意修改
     * 你可以在这里进行对自己的策略进行测试，如果不喜欢这种测试方式，可以直接删除main函数
     * @param args
     * @throws IOException
     */
    public static void main(String[] args) throws IOException {



        // 定义cpu的数量
        int cpuNumber = 2;
        // 定义测试文件
        String filename = "src/testFile/tests/rand_8.csv";
//        String filename = "src/testFile/textSample.txt";

        BottomMonitor bottomMonitor = new BottomMonitor(filename,cpuNumber);
        BottomService bottomService = new BottomService(bottomMonitor);
        Schedule schedule =  new S161250103();

        schedule.setBottomService(bottomService);

        //外部调用实现类
        for(int i = 0 ; i < 1000 ; i++){
            Task[] tasks = bottomMonitor.getTaskArrived();
            int[] cpuOperate = new int[cpuNumber];

            // 结果返回给cpuOperate
            schedule.ProcessSchedule(tasks,cpuOperate);

//            for (int i1 : cpuOperate) {
//                System.out.print(i1 + " ");
//            }
//            System.out.println();

            try {
                bottomService.runCpu(cpuOperate);
            } catch (Exception e) {
                System.out.println("Fail: "+e.getMessage());
                e.printStackTrace();
                return;
            }
            bottomMonitor.increment();
        }

        //打印统计结果
        bottomMonitor.printStatistics();
        System.out.println();

        //打印任务队列
        bottomMonitor.printTaskArrayLog();
        System.out.println();

        //打印cpu日志
        bottomMonitor.printCpuLog();


        if(!bottomMonitor.isAllTaskFinish()){
            System.out.println(" Fail: At least one task has not been completed! ");
        }
    }

}
