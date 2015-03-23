package myschedule.quartz.extra;

import org.junit.Assert;
import org.junit.Test;
import org.quartz.*;

import java.lang.reflect.Method;
import java.util.*;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Integration test for SchedulerTemplate.
 *
 * @author Zemian Deng <saltnlight5@gmail.com>
 */
public class SchedulerTemplateTest {

    @Test
    public void testScheduleCronJob() throws Exception {
        SchedulerTemplate st = new SchedulerTemplate();
        TestJob.resetResult();
        st.scheduleCronJob("test", "* * * * * ?", TestJob.class);
        st.startAndShutdown(1100);
        assertThat(TestJob.jobResult.executionTimes.size(), greaterThanOrEqualTo(2));
    }

    @Test
    public void testScheduleOnetimeJob() throws Exception {
        SchedulerTemplate st = new SchedulerTemplate();
        TestJob.resetResult();
        st.scheduleSimpleJob("test", 1, 0, TestJob.class);
        st.startAndShutdown(99);
        assertThat(TestJob.jobResult.executionTimes.size(), is(1));
    }

    @Test
    public void testScheduleSimpleJob() throws Exception {
        SchedulerTemplate st = new SchedulerTemplate();
        TestJob.resetResult();
        st.scheduleSimpleJob("test", 2, 500, TestJob.class);
        st.startAndShutdown(1300);
        assertThat(TestJob.jobResult.executionTimes.size(), is(2));

        st = new SchedulerTemplate();
        TestJob.resetResult();
        st.scheduleSimpleJob(JobKey.jobKey("test"), 2, 500, TestJob.class, null, new Date(), null);
        st.startAndShutdown(1300);
        assertThat(TestJob.jobResult.executionTimes.size(), is(2));
    }

    @Test
    public void testSchedulerMethodsDelegation() throws Exception {
        Scheduler mockedScheduler = mock(Scheduler.class);
        SchedulerTemplate st = new SchedulerTemplate(mockedScheduler);

        JobDetail jobDetail = null;
        JobKey jobKey = null;
        Trigger trigger = null;
        TriggerKey triggerKey = null;
        // Just selectively pick some methods to check it's been delegated properly.
        // TODO: Can we automatically verify all methods in org.quartz.Scheduler?
        st.addJob(jobDetail, false);
        verify(mockedScheduler).addJob(jobDetail, false);
        st.deleteJob(jobKey);
        verify(mockedScheduler).deleteJob(jobKey);
        st.scheduleJob(trigger);
        verify(mockedScheduler).scheduleJob(trigger);
        st.scheduleJob(jobDetail, trigger);
        verify(mockedScheduler).scheduleJob(jobDetail, trigger);
        st.unscheduleJob(triggerKey);
        verify(mockedScheduler).unscheduleJob(triggerKey);
        st.start();
        verify(mockedScheduler).start();
        st.shutdown();
        verify(mockedScheduler).shutdown();
    }

    @Test
    public void testSchedulerInterfaceSignatures() throws Exception {
        // Our SchedulerTemplate method signatures..
        Class<SchedulerTemplate> schedulerTemplateClass = SchedulerTemplate.class;
        Map<String, Method> schedulerTemplateMethods = new HashMap<String, Method>();
        for (Method method : schedulerTemplateClass.getMethods()) {
            String key = createSignatureKey(method);
            schedulerTemplateMethods.put(key, method);
        }

        // Expected Quartz Scheduler method signatures.
        Class<Scheduler> schedulerClass = Scheduler.class;
        for (Method expectedMethod : schedulerClass.getMethods()) {
            String expectedMethodSig = createSignatureKey(expectedMethod);
            Method actualMethod = schedulerTemplateMethods.get(expectedMethodSig);
            if (actualMethod == null) {
                Assert.fail("Method '" + expectedMethodSig + "' is missing in SchedulerTemplate class.");
            }
            String actualMethodSig = createSignatureKey(actualMethod);
            assertThat(actualMethodSig, is(expectedMethodSig));
        }
    }

    /**
     * In order to compare two classes method signature, we need to use method name and unique
     * parameters as signature. We can extract parameter string from method.toString() between
     * the '(' and ')'.
     *
     * @param method
     * @return unique method signature.
     */
    private String createSignatureKey(Method method) {
        String mStr = method.toString();
        mStr = mStr.substring(mStr.indexOf("("));
        mStr = mStr.substring(0, mStr.indexOf(")"));
        String ret = method.getName() + mStr;
        return ret;
    }

    public static class TestJob implements Job {
        static Result jobResult = new Result();

        static void resetResult() {
            jobResult = new Result();
        }

        @Override
        public void execute(JobExecutionContext context) throws JobExecutionException {
            jobResult.executionTimes.add(new Date());
        }

        public static class Result {
            List<Date> executionTimes = new ArrayList<Date>();
        }
    }
}
