package edu.oslab.threadpool;

@FunctionalInterface
public interface RecoveryAdvisor {
    RecoveryDecision choose(RecoveryContext context) throws Exception;
}
