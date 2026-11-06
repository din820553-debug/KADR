package com.kadr.app;

import org.junit.Test;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.*;

public class MomentSelectorTest {
    @Test public void selectsSeveralSeparateMomentsFromOneEpisode() {
        List<MomentSelector.Sample> samples=Arrays.asList(
            new MomentSelector.Sample(10000,90),new MomentSelector.Sample(11000,89),
            new MomentSelector.Sample(12000,88),new MomentSelector.Sample(80000,75),
            new MomentSelector.Sample(140000,70));
        List<MomentSelector.Window> windows=MomentSelector.select(samples,180000,20000,3,false);
        assertEquals(3,windows.size());
        for(int i=0;i<windows.size();i++){
            assertEquals(20000,windows.get(i).endMs-windows.get(i).startMs);
            if(i>0)assertTrue(windows.get(i-1).endMs<=windows.get(i).startMs);
        }
    }
    @Test public void boundariesStayInsideShortEpisode() {
        List<MomentSelector.Window> windows=MomentSelector.select(
            Arrays.asList(new MomentSelector.Sample(1000,60),new MomentSelector.Sample(5000,90)),8000,30000,10,false);
        assertEquals(1,windows.size());assertEquals(0,windows.get(0).startMs);assertEquals(8000,windows.get(0).endMs);
    }
    @Test public void calmModeRanksQuietFramesFirst() {
        List<MomentSelector.Sample> samples=Arrays.asList(new MomentSelector.Sample(10000,70),new MomentSelector.Sample(90000,5));
        assertEquals(5,MomentSelector.select(samples,120000,15000,1,true).get(0).score,0);
        assertEquals(70,MomentSelector.select(samples,120000,15000,1,false).get(0).score,0);
    }
    @Test public void countIsMaximumAndDoesNotDuplicateNearbyPeaks() {
        ArrayList<MomentSelector.Sample> samples=new ArrayList<>();for(int i=0;i<10;i++)samples.add(new MomentSelector.Sample(10000+i*100,100-i));
        assertEquals(1,MomentSelector.select(samples,200000,30000,10,false).size());
    }
    @Test public void handlesEmptyOrInvalidInputs() {
        assertTrue(MomentSelector.select(new ArrayList<>(),1000,100,5,false).isEmpty());
        assertTrue(MomentSelector.select(Arrays.asList(new MomentSelector.Sample(100,1)),0,100,3,false).isEmpty());
    }
    @Test public void canFindTenMomentsAcrossWholeEpisode() {
        ArrayList<MomentSelector.Sample> samples=new ArrayList<>();for(int i=0;i<10;i++)samples.add(new MomentSelector.Sample(i*90000+20000,i));
        assertEquals(10,MomentSelector.select(samples,900000,30000,10,false).size());
    }
}
