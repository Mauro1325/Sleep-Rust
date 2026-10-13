package team.creative.playerrevive.cap;
import team.creative.playerrevive.api.IBleeding;
/** Same state transitions as cap/Bleeding.java of 2.0.31 (bleedTime=1200). */
public class Bleeding implements IBleeding {
    private boolean bleeding; private int timeLeft; private int downedTime;
    public boolean isBleeding() { return bleeding; }
    public boolean bledOut() { return bleeding && timeLeft <= 0; }
    public void forceBledOut() { bleeding = true; timeLeft = 0; }
    public void knockOut(Object player, Object source) { bleeding = true; downedTime = 0; timeLeft = 1200; }
    public void revive() { bleeding = false; timeLeft = 0; downedTime = 0; }
    public int timeLeft() { return timeLeft; }
    public int downedTime() { return downedTime; }
}
