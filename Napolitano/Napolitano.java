package Napolitano;

import robocode.*;
import robocode.util.Utils;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Napolitano — robô de combate.
 *
 * IDEIA GERAL
 * -----------
 * O Robocode nao deixa a gente enxergar as balas do inimigo. O que da pra ver e
 * a ENERGIA dele: quando cai entre 0.1 e 3.0 num tick, foi tiro. Nesse instante
 * criamos uma ONDA: um circulo que nasce na posicao do inimigo e cresce na
 * velocidade da bala (20 - 3*poder). Nao sabemos o angulo exato da bala, mas
 * sabemos o circulo -- e sabemos que a bala so pode ter saido dentro do "leque"
 * de angulos que da pra acertar um robo que anda a no maximo 8 de velocidade.
 *
 * MOVIMENTO
 * ---------
 * A cada tick simulamos 21 planos de fuga diferentes (2 sentidos x 5 angulos x
 * 2 velocidades, + ficar parado) usando a fisica real do jogo. Para cada plano
 * descobrimos em que ponto CADA onda ativa nos intercepta, e somamos o perigo
 * daquele ponto. Escolhemos o plano de menor perigo total.
 *
 * Consequencia (que era o pedido): se uma onda for impossivel de esquivar, ela
 * da o mesmo perigo em todos os planos e simplesmente nao influencia a escolha
 * -- o robo automaticamente "aceita" esse tiro e otimiza os outros.
 *
 * PAREDE
 * ------
 * suavizarParede() dobra a rota pra acompanhar a parede em vez de bater nela.
 * Se o desvio ideal for pra esquerda mas a parede esquerda estiver colada, ele
 * curva pro outro lado sozinho.
 */
public class Napolitano extends AdvancedRobot {

	// ---------------------------------------------------------------- fisica
	private static final double VEL_MAX      = 8.0;
	private static final double RAIO_ROBO    = 18.0;  // meia largura da caixa 36x36
	private static final double MARGEM       = 40.0;  // margem de seguranca da parede
	private static final int    BINS         = 31;    // resolucao do aprendizado de mira
	private static final int    MAX_PASSOS   = 90;    // horizonte da simulacao (ticks)

	// ---------------------------------------------------------------- estado
	private final Map<String, Inimigo> inimigos = new HashMap<String, Inimigo>();
	private final List<Onda> ondas = new ArrayList<Onda>();
	private final List<Point2D.Double> rota = new ArrayList<Point2D.Double>();

	private double larguraArena, alturaArena;
	private double refX, refY;      // centro da orbita (origem da onda mais urgente)
	private Inimigo alvo;

	// nossa posicao no tick anterior: e de la que as ondas inimigas "miraram"
	private double meuXAnt, meuYAnt, meuHeadingAnt, minhaVelAnt;

	// os 21 planos de fuga, montados uma vez so
	private static final List<Plano> PLANOS = new ArrayList<Plano>();
	static {
		double[] offsets = { -0.5, -0.25, 0, 0.25, 0.5 };   // desvio do perpendicular
		double[] velocidades = { 8, 4 };
		for (int s = -1; s <= 1; s += 2)
			for (int a = 0; a < offsets.length; a++)
				for (int v = 0; v < velocidades.length; v++)
					PLANOS.add(new Plano(s, offsets[a], velocidades[v]));
		PLANOS.add(new Plano(1, 0, 0));                      // parar tambem e uma opcao
	}

	// ==================================================================== run
	public void run() {
		setColors(new Color(200, 40, 40), Color.WHITE, new Color(240, 225, 190),
		          new Color(255, 90, 60), Color.WHITE);
		setAdjustGunForRobotTurn(true);
		setAdjustRadarForGunTurn(true);
		setAdjustRadarForRobotTurn(true);

		larguraArena = getBattleFieldWidth();
		alturaArena  = getBattleFieldHeight();

		// novo round: as ondas velhas nao existem mais e todo mundo revive.
		// o que NAO se apaga e o perigoAprendido -- e ele que acumula rounds.
		ondas.clear();
		for (Inimigo i : inimigos.values()) i.reiniciarRound();

		while (true) {
			limparOndas();
			alvo = escolherAlvo();
			girarRadar();
			if (alvo != null) mirar(alvo);
			mover();
			// guarda o estado deste tick: os eventos que chegarem no execute()
			// se referem a tiros disparados a partir DAQUI
			meuXAnt = getX();
			meuYAnt = getY();
			meuHeadingAnt = getHeadingRadians();
			minhaVelAnt = getVelocity();
			execute();
		}
	}

	// ================================================================ eventos
	public void onScannedRobot(ScannedRobotEvent e) {
		Inimigo i = inimigos.get(e.getName());
		if (i == null) {
			i = new Inimigo(e.getName());
			inimigos.put(e.getName(), i);
		}
		double ang = getHeadingRadians() + e.getBearingRadians();
		double x = getX() + Math.sin(ang) * e.getDistance();
		double y = getY() + Math.cos(ang) * e.getDistance();

		// queda de energia entre 0.1 e 3.0 = tiro. Acima de 3 e dano recebido.
		double queda = i.energia - e.getEnergy();
		if (i.visto && queda >= 0.09 && queda <= 3.01) {
			criarOnda(i, queda);
		}
		i.atualizar(x, y, e.getEnergy(), e.getHeadingRadians(), e.getVelocity(), getTime());
	}

	public void onHitByBullet(HitByBulletEvent e) {
		// achamos qual onda era essa bala e aprendemos o angulo que ele usa
		Onda achou = null;
		double menorErro = 60;
		for (int k = 0; k < ondas.size(); k++) {
			Onda o = ondas.get(k);
			if (Math.abs(o.velocidade - e.getVelocity()) > 0.01) continue;
			double erro = Math.abs(o.raio(getTime()) - dist(o.origemX, o.origemY, getX(), getY()));
			if (erro < menorErro) { menorErro = erro; achou = o; }
		}
		if (achou != null) {
			achou.dono.aprender(achou.fatorDe(getX(), getY()));
			ondas.remove(achou);
		}
	}

	public void onHitRobot(HitRobotEvent e) {
		// colisao: atira forte de perto e sai de cima
		if (getGunHeat() == 0) setFire(3);
		setBack(50);
	}

	public void onRobotDeath(RobotDeathEvent e) {
		Inimigo i = inimigos.get(e.getName());
		if (i != null) i.vivo = false;
	}

	// ============================================================== movimento
	private void mover() {
		// foco da orbita: a onda mais urgente; sem ondas, o alvo; sem alvo
		// (comeco do round), o centro -- o importante e nunca ficar parado
		Onda urgente = ondaMaisUrgente();
		if (urgente != null)  { refX = urgente.origemX; refY = urgente.origemY; }
		else if (alvo != null){ refX = alvo.x;          refY = alvo.y;          }
		else                  { refX = larguraArena / 2; refY = alturaArena / 2; }

		Plano melhor = PLANOS.get(0);
		double melhorNota = Double.MAX_VALUE;
		for (int k = 0; k < PLANOS.size(); k++) {
			double nota = simular(PLANOS.get(k), null);
			if (nota < melhorNota) { melhorNota = nota; melhor = PLANOS.get(k); }
		}
		rota.clear();
		simular(melhor, rota);   // refaz o vencedor so pra desenhar na tela

		// executa o primeiro passo do plano vencedor
		double dir = rumoDoPlano(melhor, getX(), getY());
		double giro = Utils.normalRelativeAngle(dir - getHeadingRadians());
		double re = 1;
		if (Math.abs(giro) > Math.PI / 2) {           // e mais rapido ir de re
			giro = Utils.normalRelativeAngle(giro + Math.PI);
			re = -1;
		}
		setTurnRightRadians(giro);
		setMaxVelocity(melhor.velAlvo);
		setAhead(re * 100);
	}

	/**
	 * Roda a fisica do jogo pra frente seguindo um plano e devolve o perigo
	 * total acumulado. Nota menor = melhor. Se 'traco' nao for nulo, guarda o
	 * caminho previsto (usado no onPaint).
	 */
	private double simular(Plano p, List<Point2D.Double> traco) {
		double x = getX(), y = getY(), h = getHeadingRadians(), v = getVelocity();
		long t = getTime();
		boolean[] jaContada = new boolean[ondas.size()];
		int restantes = ondas.size();
		double nota = 0;

		for (int passo = 1; passo <= MAX_PASSOS && (restantes > 0 || passo <= 20); passo++) {
			t++;

			double rumo = rumoDoPlano(p, x, y);
			double giro = Utils.normalRelativeAngle(rumo - h);
			double re = 1;
			if (Math.abs(giro) > Math.PI / 2) {
				giro = Utils.normalRelativeAngle(giro + Math.PI);
				re = -1;
			}
			double giroMax = Math.toRadians(10 - 0.75 * Math.abs(v));
			h += limitar(giro, -giroMax, giroMax);
			v = proximaVelocidade(v, re * p.velAlvo);
			x += Math.sin(h) * v;
			y += Math.cos(h) * v;

			// bateu na parede: para o robo e leva multa (queremos evitar isso)
			double cx = limitar(x, RAIO_ROBO, larguraArena - RAIO_ROBO);
			double cy = limitar(y, RAIO_ROBO, alturaArena - RAIO_ROBO);
			if (cx != x || cy != y) { x = cx; y = cy; v = 0; nota += 6; }

			if (traco != null) traco.add(new Point2D.Double(x, y));

			// alguma onda nos alcanca neste tick?
			for (int k = 0; k < ondas.size(); k++) {
				if (jaContada[k]) continue;
				Onda o = ondas.get(k);
				if (o.raio(t) + o.velocidade < dist(o.origemX, o.origemY, x, y) - RAIO_ROBO) continue;
				jaContada[k] = true;
				restantes--;
				// ondas que chegam logo pesam mais: a previsao delas e mais confiavel
				double peso = o.dano() / (1 + passo * 0.08);
				nota += peso * o.perigo(o.fatorDe(x, y));
			}
		}

		// criterios de posicionamento (so desempatam, nunca mandam mais que as ondas)
		for (Inimigo i : inimigos.values()) {
			if (!i.vivo) continue;
			double d = dist(i.x, i.y, x, y);
			nota += 60 / Math.max(d, 60);            // nao ficar colado no inimigo
		}
		nota += 3 * (Math.abs(x - larguraArena / 2) / larguraArena
		           + Math.abs(y - alturaArena / 2) / alturaArena);  // leve preferencia pelo miolo
		return nota;
	}

	/** Rumo desejado: orbita ao redor do foco, com desvio angular e desvio de parede. */
	private double rumoDoPlano(Plano p, double x, double y) {
		double paraFoco = Math.atan2(x - refX, y - refY);
		return suavizarParede(x, y, paraFoco + p.sentido * (Math.PI / 2 + p.offset), p.sentido);
	}

	/**
	 * Gira o rumo desejado ate que o ponto ~130 a frente caia dentro da area
	 * segura. E isso que faz o robo escolher o outro lado quando a parede corta
	 * o desvio "natural".
	 */
	private double suavizarParede(double x, double y, double rumo, int sentido) {
		for (int i = 0; i < 40; i++) {
			double px = x + Math.sin(rumo) * 130;
			double py = y + Math.cos(rumo) * 130;
			if (px > MARGEM && px < larguraArena - MARGEM
			 && py > MARGEM && py < alturaArena - MARGEM) break;
			rumo += sentido * 0.14;
		}
		return rumo;
	}

	// =================================================================== tiro
	private void mirar(Inimigo i) {
		double d = dist(i.x, i.y, getX(), getY());
		double poder = escolherPoder(i, d);
		double velBala = 20 - 3 * poder;

		Point2D.Double p = preverPosicao(i, velBala);
		double ang = Math.atan2(p.x - getX(), p.y - getY());
		setTurnGunRightRadians(Utils.normalRelativeAngle(ang - getGunHeadingRadians()));

		// so atira com o canhao alinhado dentro da largura do inimigo
		boolean alinhado = Math.abs(getGunTurnRemainingRadians()) < Math.atan(RAIO_ROBO / d);
		if (alinhado && getGunHeat() == 0 && getEnergy() > poder + 0.4) {
			setFire(poder);
		}
	}

	/**
	 * Calor do canhao ("histamina"): depois do tiro ele fica 1 + poder/5 quente
	 * e esfria 0.1 por tick. Poder 3 = 16 ticks parado; poder 1 = 12 ticks.
	 * Logo tiro forte so compensa quando a chance de acertar e alta (perto).
	 * De longe vale mais tiro fraco: sai mais vezes e a bala e mais rapida,
	 * o que dificulta a esquiva do outro.
	 */
	private double escolherPoder(Inimigo i, double d) {
		double poder = 600 / d;                      // 200 -> 3 | 300 -> 2 | 600 -> 1
		if (getEnergy() < 30) poder = Math.min(poder, getEnergy() / 8);
		poder = Math.min(poder, i.energia / 4 + 0.1); // nao desperdica no golpe final
		if (getOthers() > 2) poder = Math.min(poder, 2);  // melee: energia e vida
		return limitar(poder, 0.1, 3);
	}

	/** Mira circular: repete o giro e a velocidade atuais do inimigo ate a bala chegar. */
	private Point2D.Double preverPosicao(Inimigo i, double velBala) {
		double x = i.x, y = i.y, h = i.heading, v = i.velocidade;
		for (int t = 1; t <= 110 && t * velBala < dist(x, y, getX(), getY()); t++) {
			h += i.taxaGiro;
			x += Math.sin(h) * v;
			y += Math.cos(h) * v;
			double cx = limitar(x, RAIO_ROBO, larguraArena - RAIO_ROBO);
			double cy = limitar(y, RAIO_ROBO, alturaArena - RAIO_ROBO);
			if (cx != x || cy != y) { x = cx; y = cy; v = 0; }  // ele bateria na parede
		}
		return new Point2D.Double(x, y);
	}

	// ================================================================== radar
	private void girarRadar() {
		// 1x1 com dado fresco: trava no inimigo. Caso contrario varre tudo.
		if (getOthers() == 1 && alvo != null && getTime() - alvo.tempo < 3) {
			double ang = Math.atan2(alvo.x - getX(), alvo.y - getY());
			double giro = Utils.normalRelativeAngle(ang - getRadarHeadingRadians());
			setTurnRadarRightRadians(giro + (giro < 0 ? -0.35 : 0.35));
		} else {
			setTurnRadarRightRadians(Double.POSITIVE_INFINITY);
		}
	}

	// ================================================================== ondas
	private void criarOnda(Inimigo i, double poder) {
		Onda o = new Onda();
		o.dono = i;
		o.origemX = i.x;                 // posicao dele no scan anterior
		o.origemY = i.y;
		o.poder = poder;
		o.velocidade = 20 - 3 * poder;
		o.tempoDisparo = getTime() - 1;
		o.maxEscape = Math.asin(VEL_MAX / o.velocidade);

		double dx = meuXAnt - o.origemX, dy = meuYAnt - o.origemY;
		o.anguloParaNos = Math.atan2(dx, dy);
		double d = Math.hypot(dx, dy);
		o.larguraFator = Math.max(Math.atan(RAIO_ROBO / d) / o.maxEscape, 0.06);

		// velocidade lateral nossa vista por ele: define o "para frente" (fator +1)
		double lateral = minhaVelAnt * Math.sin(meuHeadingAnt - o.anguloParaNos);
		o.sentido = lateral < 0 ? -1 : 1;
		// onde a mira linear dele acertaria, em unidades de fator (0..1)
		o.fatorLinear = Math.asin(limitar(Math.abs(lateral) / o.velocidade, -1, 1)) / o.maxEscape;

		ondas.add(o);
	}

	private void limparOndas() {
		for (int k = ondas.size() - 1; k >= 0; k--) {
			Onda o = ondas.get(k);
			if (o.raio(getTime()) > dist(o.origemX, o.origemY, getX(), getY()) + 50) ondas.remove(k);
		}
	}

	private Onda ondaMaisUrgente() {
		Onda melhor = null;
		double menorFalta = Double.MAX_VALUE;
		for (int k = 0; k < ondas.size(); k++) {
			Onda o = ondas.get(k);
			double falta = dist(o.origemX, o.origemY, getX(), getY()) - o.raio(getTime());
			if (falta > 0 && falta < menorFalta) { menorFalta = falta; melhor = o; }
		}
		return melhor;
	}

	private Inimigo escolherAlvo() {
		Inimigo melhor = null;
		double menor = Double.MAX_VALUE;
		for (Inimigo i : inimigos.values()) {
			if (!i.vivo || !i.visto) continue;
			double d = dist(i.x, i.y, getX(), getY());
			if (getTime() - i.tempo > 12) d += 400;   // dado velho vale menos
			if (d < menor) { menor = d; melhor = i; }
		}
		return melhor;
	}

	// ================================================================= pintar
	public void onPaint(Graphics2D g) {
		g.setColor(new Color(255, 80, 80, 140));
		for (int k = 0; k < ondas.size(); k++) {
			Onda o = ondas.get(k);
			int r = (int) o.raio(getTime());
			g.drawOval((int) o.origemX - r, (int) o.origemY - r, r * 2, r * 2);
		}
		g.setColor(new Color(120, 255, 120, 200));
		for (int k = 0; k < rota.size(); k++) {
			Point2D.Double p = rota.get(k);
			g.fillRect((int) p.x - 1, (int) p.y - 1, 3, 3);
		}
	}

	// ============================================================= utilidades
	private static double dist(double x1, double y1, double x2, double y2) {
		return Math.hypot(x1 - x2, y1 - y2);
	}

	private static double limitar(double v, double min, double max) {
		return v < min ? min : (v > max ? max : v);
	}

	private static double sq(double v) { return v * v; }

	/** Regra de aceleracao do Robocode: acelera 1/tick, freia 2/tick, teto 8. */
	private static double proximaVelocidade(double v, double alvo) {
		double nova = v;
		if (alvo > v)      nova = Math.min(alvo, v + (v < 0 ? 2 : 1));
		else if (alvo < v) nova = Math.max(alvo, v - (v > 0 ? 2 : 1));
		return limitar(nova, -VEL_MAX, VEL_MAX);
	}

	// ================================================================ classes
	/** Um plano de fuga candidato. */
	private static class Plano {
		final int sentido;      // +1 horario, -1 anti-horario ao redor do foco
		final double offset;    // desvio do perpendicular (aproximar / afastar)
		final double velAlvo;
		Plano(int sentido, double offset, double velAlvo) {
			this.sentido = sentido; this.offset = offset; this.velAlvo = velAlvo;
		}
	}

	/** Ultimo estado conhecido de um inimigo + o que aprendemos da mira dele. */
	private static class Inimigo {
		final String nome;
		final double[] perigoAprendido = new double[BINS];
		double x, y, energia = 100, heading, velocidade, taxaGiro;
		long tempo;
		boolean vivo = true, visto = false;

		Inimigo(String nome) { this.nome = nome; }

		void reiniciarRound() { vivo = true; visto = false; energia = 100; }

		void atualizar(double x, double y, double energia, double heading,
		               double velocidade, long tempo) {
			long dt = tempo - this.tempo;
			if (visto && dt > 0 && dt < 5) {
				taxaGiro = limitar(Utils.normalRelativeAngle(heading - this.heading) / dt,
				                   -0.18, 0.18);
			}
			this.x = x; this.y = y; this.energia = energia; this.heading = heading;
			this.velocidade = velocidade; this.tempo = tempo; this.visto = true;
		}

		/** Levou um tiro nesse fator: marca a regiao como perigosa pra sempre. */
		void aprender(double fator) {
			int idx = binDe(fator);
			for (int i = 0; i < BINS; i++) perigoAprendido[i] += 1.0 / (1 + sq(i - idx));
		}

		static int binDe(double fator) {
			return (int) limitar(Math.round((fator + 1) / 2 * (BINS - 1)), 0, BINS - 1);
		}
	}

	/** Uma bala inimiga que a gente sabe que existe, mas nao sabe o angulo. */
	private static class Onda {
		Inimigo dono;
		double origemX, origemY, velocidade, poder;
		double anguloParaNos;   // angulo origem -> nos, no instante do disparo
		double maxEscape;       // maior angulo que da pra escapar (leque total)
		double larguraFator;    // nossa largura convertida pra unidade de fator
		double fatorLinear;     // onde a mira linear dele cairia
		int sentido;            // nosso sentido lateral no disparo (+1 = "pra frente")
		long tempoDisparo;

		double raio(long t) { return (t - tempoDisparo) * velocidade; }

		double dano() { return 4 * poder + (poder > 1 ? 2 * (poder - 1) : 0); }

		/**
		 * Converte um ponto em "fator": -1 = fugindo pra tras ao maximo,
		 * 0 = onde estavamos quando ele atirou, +1 = fugindo pra frente ao maximo.
		 */
		double fatorDe(double x, double y) {
			double desvio = Utils.normalRelativeAngle(
					Math.atan2(x - origemX, y - origemY) - anguloParaNos);
			return limitar(desvio / maxEscape * sentido, -1, 1);
		}

		/**
		 * Perigo de ser interceptado nesse fator. Duas suspeitas fixas (mira
		 * direta no fator 0 e mira linear no fatorLinear) mais tudo que esse
		 * inimigo ja acertou na gente.
		 */
		double perigo(double fator) {
			return 1.4 / (1 + sq(fator / larguraFator))
			     + 1.0 / (1 + sq((fator - fatorLinear) / larguraFator))
			     + dono.perigoAprendido[Inimigo.binDe(fator)];
		}
	}
}
