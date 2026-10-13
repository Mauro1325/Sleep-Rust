package team.creative.playerrevive.api;
public interface IBleeding {
    boolean isBleeding();
    boolean bledOut();
    void forceBledOut();
    void knockOut(Object player, Object source);
    void revive();
    int timeLeft();
    int downedTime();
}
