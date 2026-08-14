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
 * Napolitano — robo de combate.
 *
 * HIERARQUIA DE DECISAO (esta ordem e a regra de ouro do robo)
 * ------------------------------------------------------------
 *  1) NAO BATER NA PAREDE  -> P_BATIDA (2000) e P_ZONA (ate 400)
 *  2) NAO TOMAR TIRO       -> P_ONDA (dezenas por onda ativa)
 *  3) FICAR LONGE          -> P_DIST + P_COLADO, com TETO_DIST em 200
 *
 * Cada tick o robo simula 32 futuros (16 direcoes x 2 velocidades) com a fisica
 * real do jogo e escolhe o de menor perigo somado. Como o criterio 3 tem TETO e
 * esse teto e menor que o custo de raspar na parede, a hierarquia acima e uma
 * garantia numerica, nao um ajuste de gosto: nenhuma quantidade de inimigos
 * colados convence o robo a encostar na parede.
 *
 * O ROBO NAO PERSEGUE NINGUEM
 * ---------------------------
 * O movimento nao tem "alvo": ele escolhe entre 16 direcoes absolutas da arena.
 * O inimigo so entra na conta como REPULSAO (queremos distancia) e como origem
 * das ondas de bala. Nada no movimento tenta se aproximar de alguem — se o
 * combate acabar de perto, foi por acaso (o outro veio, ou a parede prendeu).
 *
 * ONDAS (esquiva)
 * ---------------
 * O Robocode nao mostra as balas inimigas, mas mostra a ENERGIA dele: queda
 * entre 0.1 e 3.0 num tick = tiro. Nesse instante nasce uma ONDA: circulo que
 * cresce a 20-3*poder a partir da posicao dele. Nao sabemos o angulo da bala,
 * mas sabemos o circulo e o "leque" de angulos possiveis. A simulacao descobre
 * em que ponto do leque cada plano de fuga seria interceptado e foge do ponto
 * mais provavel.
 *
 * TIRO
 * ----
 * Mira preditiva (circular, com previsao de parede) sempre no inimigo MAIS
 * PROXIMO — perto a bala erra menos e cada acerto devolve 3*poder de energia.
 * Se um inimigo chega perto demais (DIST_CURTA), entra o modo rajada: poder
 * maximo em toda janela de canhao frio, gastando energia de proposito, porque
 * a essa distancia a esquiva dele quase nao existe.
 */
public class Napolitano extends AdvancedRobot {

	// ------------------------------------------------------------ fisica do jogo
	private static final double VEL_MAX     = 8.0;
	private static final double RAIO_ROBO   = 18.0;  // meia largura da caixa 36x36
	private static final int    BINS        = 31;    // resolucao do aprendizado de mira
	private static final int    PASSOS      = 32;    // horizonte da simulacao (ticks)
	private static final int    N_DIRECOES  = 16;    // direcoes de fuga testadas por tick
	private static final double[] VELOCIDADES = { 8, 4 };

	// ------------------------------------------------- pesos (ver hierarquia acima)
	private static final double P_BATIDA  = 2000;  // 1) encostou na parede: proibido
	private static final double P_ZONA    = 400;   // 1) entrou na faixa colada na parede
	private static final double P_ONDA    = 1;     // 2) perigo de bala (dano x probabilidade)
	private static final double P_DIST    = 30;    // 3) ficar longe dos inimigos
	private static final double P_COLADO  = 150;   // 3) inimigo colado: dano de ram, sai ja
	private static final double P_INERCIA = 4;     // anti-tremedeira: evita trocar de ideia a toa
	private static final double TETO_DIST = 200;   // teto da soma do criterio 3 (< P_ZONA):
	                                               // garante que nem 5 inimigos colados
	                                               // convencam o robo a raspar na parede

	private static final double MARGEM_MAX  = 50.0;   // largura da faixa perigosa da parede
	private static final double OLHAR       = 140.0;  // quanto a frente olhamos ao curvar
	private static final double DIST_IDEAL  = 450.0;  // a partir daqui "longe" ja e longe o bastante
	private static final double DIST_CURTA  = 120.0;  // gatilho do modo rajada

	// ------------------------------------------------------------------- estado
	private final Map<String, Inimigo> inimigos = new HashMap<String, Inimigo>();
	private final List<Onda> ondas = new ArrayList<Onda>();
	private final List<Point2D.Double> rota = new ArrayList<Point2D.Double>();

	private double larguraArena, alturaArena, margem;
	private Inimigo alvo;

	// nossa posicao no tick anterior: e de la que as ondas inimigas "miraram"
	private double meuXAnt, meuYAnt, meuHeadingAnt, minhaVelAnt;

	// onde cada inimigo vivo deve estar no fim do horizonte de simulacao.
	// Recalculado 1x por tick (nao depende da direcao testada) e reusado nas 32
	// simulacoes — e o que permite fugir de quem VEM, e nao de onde ele estava.
	private final List<Point2D.Double> previstos = new ArrayList<Point2D.Double>();

	// ===========================================================================
	// run
	// ===========================================================================
	public void run() {
		setColors(new Color(200, 40, 40), Color.WHITE, new Color(240, 225, 190),
		          new Color(255, 90, 60), Color.WHITE);
		setAdjustGunForRobotTurn(true);
		setAdjustRadarForGunTurn(true);
		setAdjustRadarForRobotTurn(true);

		larguraArena = getBattleFieldWidth();
		alturaArena  = getBattleFieldHeight();
		// em arena pequena a faixa de seguranca nao pode engolir o campo inteiro,
		// senao nao sobraria nenhum ponto "seguro" e a curva de parede nunca fecharia
		margem = Math.max(1, Math.min(MARGEM_MAX,
		                  Math.min(larguraArena, alturaArena) / 2 - RAIO_ROBO - 1));

		// novo round: ondas velhas nao existem mais e todo mundo revive.
		// o que NAO se apaga e o aprendizado de mira — ele acumula entre rounds.
		ondas.clear();
		for (Inimigo i : inimigos.values()) i.reiniciarRound();

		while (true) {
			limparOndas();
			alvo = escolherAlvo();
			girarRadar();
			mover();                       // 1o parede, 2o balas, 3o distancia
			if (alvo != null) mirar(alvo);
			// guarda o estado deste tick: os tiros detectados no proximo scan
			// foram mirados a partir DAQUI
			meuXAnt = getX();
			meuYAnt = getY();
			meuHeadingAnt = getHeadingRadians();
			minhaVelAnt = getVelocity();
			execute();
		}
	}

	// ===========================================================================
	// eventos
	// ===========================================================================
	public void onScannedRobot(ScannedRobotEvent e) {
		Inimigo i = inimigos.get(e.getName());
		if (i == null) {
			i = new Inimigo();
			inimigos.put(e.getName(), i);
		}
		double ang = getHeadingRadians() + e.getBearingRadians();
		double x = getX() + Math.sin(ang) * e.getDistance();
		double y = getY() + Math.cos(ang) * e.getDistance();

		// queda de energia entre 0.1 e 3.0 = tiro. Acima de 3 e dano que ele levou.
		double queda = i.energia - e.getEnergy();
		if (i.visto && queda >= 0.09 && queda <= 3.01) criarOnda(i, queda);

		i.atualizar(x, y, e.getEnergy(), e.getHeadingRadians(), e.getVelocity(), getTime());
	}

	public void onHitByBullet(HitByBulletEvent e) {
		// descobre qual onda era essa bala e aprende em que angulo ele gosta de mirar
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

	// Nao existe onHitWall/onHitRobot aqui de proposito: mover() e mirar() sao
	// reexecutados DEPOIS dos eventos, todo tick, e sobrescreveriam qualquer
	// setAhead/setFire feito no evento. Bater na parede ja e punido por P_BATIDA
	// e o inimigo colado ja e empurrado por P_COLADO e alvejado pela rajada.

	public void onRobotDeath(RobotDeathEvent e) {
		Inimigo i = inimigos.get(e.getName());
		if (i != null) i.vivo = false;
	}

	// ===========================================================================
	// MOVIMENTO — prioridade 1: parede | 2: balas | 3: distancia
	// ===========================================================================
	private void mover() {
		preverInimigos();

		// direcao em que estamos efetivamente andando agora (de re conta invertido)
		double rumoAtual = getHeadingRadians() + (getVelocity() < 0 ? Math.PI : 0);

		double melhorRumo = rumoAtual, melhorVel = VEL_MAX;
		double melhorNota = Double.MAX_VALUE;

		for (int d = 0; d < N_DIRECOES; d++) {
			double rumo = d * 2 * Math.PI / N_DIRECOES;
			// inercia: trocar bruscamente de direcao custa tempo de curva, entao
			// so trocamos quando a nova opcao e realmente melhor
			double inercia = P_INERCIA
			               * Math.abs(Utils.normalRelativeAngle(rumo - rumoAtual)) / Math.PI;
			for (int v = 0; v < VELOCIDADES.length; v++) {
				double nota = simular(rumo, VELOCIDADES[v], null) + inercia;
				if (nota < melhorNota) {
					melhorNota = nota; melhorRumo = rumo; melhorVel = VELOCIDADES[v];
				}
			}
		}

		rota.clear();
		simular(melhorRumo, melhorVel, rota);   // refaz o vencedor so pra desenhar

		// executa o primeiro passo: a curva de parede e reaplicada agora, com a
		// posicao real, porque ela e a ultima linha de defesa contra a parede
		double rumo = desviarParede(getX(), getY(), melhorRumo);
		double giro = Utils.normalRelativeAngle(rumo - getHeadingRadians());
		double re = 1;
		if (Math.abs(giro) > Math.PI / 2) {      // e mais rapido chegar la de re
			giro = Utils.normalRelativeAngle(giro + Math.PI);
			re = -1;
		}
		setTurnRightRadians(giro);
		setMaxVelocity(melhorVel);
		setAhead(re * 100);
	}

	/**
	 * Roda a fisica real do jogo pra frente seguindo uma direcao e devolve o
	 * perigo total. Nota menor = melhor. Se 'traco' nao for nulo, guarda o
	 * caminho previsto (usado no onPaint).
	 */
	private double simular(double rumoBase, double velAlvo, List<Point2D.Double> traco) {
		double x = getX(), y = getY(), h = getHeadingRadians(), v = getVelocity();
		long t = getTime();
		boolean[] contada = new boolean[ondas.size()];
		int faltam = ondas.size();

		int batidas = 0;
		double piorParede = 0, perigoOndas = 0;

		for (int passo = 1; passo <= PASSOS; passo++) {
			t++;

			double rumo = desviarParede(x, y, rumoBase);
			double giro = Utils.normalRelativeAngle(rumo - h);
			double re = 1;
			if (Math.abs(giro) > Math.PI / 2) {
				giro = Utils.normalRelativeAngle(giro + Math.PI);
				re = -1;
			}
			double giroMax = Math.toRadians(10 - 0.75 * Math.abs(v));
			h += limitar(giro, -giroMax, giroMax);
			v = proximaVelocidade(v, re * velAlvo);
			x += Math.sin(h) * v;
			y += Math.cos(h) * v;

			// (1) parede: bateu = multa fixa enorme; chegar perto ja custa caro,
			// e custa mais quanto mais cedo acontecer (ainda da pra evitar)
			double cx = limitar(x, RAIO_ROBO, larguraArena - RAIO_ROBO);
			double cy = limitar(y, RAIO_ROBO, alturaArena - RAIO_ROBO);
			if (cx != x || cy != y) { x = cx; y = cy; v = 0; batidas++; }
			piorParede = Math.max(piorParede, perigoParede(x, y) / (1 + passo * 0.05));

			if (traco != null) traco.add(new Point2D.Double(x, y));

			// (2) alguma onda nos alcanca neste tick?
			for (int k = 0; k < ondas.size() && faltam > 0; k++) {
				if (contada[k]) continue;
				Onda o = ondas.get(k);
				if (o.raio(t) + o.velocidade < dist(o.origemX, o.origemY, x, y) - RAIO_ROBO) continue;
				contada[k] = true;
				faltam--;
				// onda que chega logo pesa mais: a previsao dela e mais confiavel
				perigoOndas += o.dano() / (1 + passo * 0.08) * o.perigo(o.fatorDe(x, y));
			}
		}

		// (3) distancia: compara a NOSSA posicao final com a posicao PREVISTA
		// dele no mesmo instante — sem isso, fugir de um perseguidor nao funciona
		double perigoPerto = 0;
		for (int k = 0; k < previstos.size(); k++) {
			Point2D.Double i = previstos.get(k);
			double d = dist(i.x, i.y, x, y);
			perigoPerto += P_DIST * sq(1 - Math.min(d, DIST_IDEAL) / DIST_IDEAL);
			// abaixo de DIST_CURTA o risco de ram e de tiro certeiro dispara —
			// ainda assim P_COLADO fica bem abaixo de P_ZONA: parede continua acima
			if (d < DIST_CURTA) perigoPerto += P_COLADO * sq(1 - d / DIST_CURTA);
		}

		// o teto e o que torna a hierarquia uma REGRA e nao so uma escolha de numeros
		return P_BATIDA * batidas + piorParede + P_ONDA * perigoOndas
		     + Math.min(perigoPerto, TETO_DIST);
	}

	/**
	 * Extrapola cada inimigo em linha reta ate o fim do horizonte, preso na arena.
	 * Dado velho (radar ainda nao voltou nele) nao e extrapolado: rumo antigo x 32
	 * ticks erraria feio e nos empurraria pro lado errado.
	 */
	private void preverInimigos() {
		previstos.clear();
		for (Inimigo i : inimigos.values()) {
			if (!i.vivo || !i.visto) continue;
			int avanco = getTime() - i.tempo <= 8 ? PASSOS : 0;
			previstos.add(new Point2D.Double(
					limitar(i.x + Math.sin(i.heading) * i.velocidade * avanco,
					        RAIO_ROBO, larguraArena - RAIO_ROBO),
					limitar(i.y + Math.cos(i.heading) * i.velocidade * avanco,
					        RAIO_ROBO, alturaArena - RAIO_ROBO)));
		}
	}

	/** Custo de estar dentro da faixa colada na parede. 0 fora dela, P_ZONA na quina. */
	private double perigoParede(double x, double y) {
		double folga = Math.min(Math.min(x, larguraArena - x),
		                        Math.min(y, alturaArena - y)) - RAIO_ROBO;
		if (folga >= margem) return 0;
		double f = (margem - Math.max(folga, 0)) / margem;
		return P_ZONA * f * f;
	}

	/**
	 * Curva o rumo desejado ate que o ponto OLHAR a frente caia dentro da area
	 * segura, girando sempre pelo lado mais curto em direcao ao centro. Como
	 * paramos assim que o ponto entra, o resultado natural e correr PARALELO a
	 * parede em vez de fugir pro meio — mantem a esquiva viva sem encostar.
	 */
	private double desviarParede(double x, double y, double rumo) {
		double paraCentro = Math.atan2(larguraArena / 2 - x, alturaArena / 2 - y);
		double passo = Utils.normalRelativeAngle(paraCentro - rumo) >= 0 ? 0.1 : -0.1;
		for (int i = 0; i < 32; i++) {
			double px = x + Math.sin(rumo) * OLHAR;
			double py = y + Math.cos(rumo) * OLHAR;
			if (px > margem && px < larguraArena - margem
			 && py > margem && py < alturaArena - margem) break;
			rumo += passo;
		}
		return rumo;
	}

	// ===========================================================================
	// TIRO — mira preditiva no mais proximo, rajada quando ele cola
	// ===========================================================================
	private void mirar(Inimigo i) {
		double d = dist(i.x, i.y, getX(), getY());
		boolean curta = d <= DIST_CURTA;         // aconteceu por acaso: nada no
		                                         // movimento procura essa distancia
		double poder = curta ? poderDeRajada(i) : escolherPoder(i, d);

		Point2D.Double p = preverPosicao(i, 20 - 3 * poder);
		double ang = Math.atan2(p.x - getX(), p.y - getY());
		setTurnGunRightRadians(Utils.normalRelativeAngle(ang - getGunHeadingRadians()));

		// so atira com o canhao dentro da largura do inimigo. De perto esse cone
		// e largo, o que faz a rajada sair em praticamente todo canhao frio.
		if (getGunHeat() == 0 && getEnergy() > poder + 0.1   // atirar ate zerar nos desliga
		 && Math.abs(getGunTurnRemainingRadians()) < Math.atan(RAIO_ROBO / d)) {
			setFire(poder);
		}
	}

	/**
	 * Calor do canhao: apos o tiro ele fica 1 + poder/5 quente e esfria 0.1 por
	 * tick. Poder 3 = 16 ticks parado, poder 1 = 12. Entao de longe compensa
	 * tiro fraco (sai mais vezes e a bala e mais rapida) e de perto compensa o
	 * tiro forte, que e o que a rajada faz.
	 */
	private double escolherPoder(Inimigo i, double d) {
		double poder = 600 / d;                          // 200 -> 3 | 300 -> 2 | 600 -> 1
		if (getEnergy() < 30) poder = Math.min(poder, getEnergy() / 8);
		poder = Math.min(poder, i.energia / 4 + 0.1);    // nao desperdica no golpe final
		if (getOthers() > 2) poder = Math.min(poder, 2); // melee: energia e vida
		return limitar(poder, 0.1, 3);
	}

	/**
	 * Modo rajada: o inimigo colou, a bala chega em poucos ticks e a esquiva
	 * dele quase nao existe — vale queimar energia no poder maximo. Cada acerto
	 * devolve 3*poder, entao aqui o gasto tende a se pagar sozinho.
	 */
	private double poderDeRajada(Inimigo i) {
		double poder = Math.min(3, i.energia / 4 + 0.1);   // nao gasta mais do que mata
		return limitar(Math.min(poder, getEnergy() - 0.3), 0.1, 3);
	}

	/** Mira circular: repete giro e velocidade atuais do inimigo ate a bala chegar. */
	private Point2D.Double preverPosicao(Inimigo i, double velBala) {
		double x = i.x, y = i.y, h = i.heading, v = i.velocidade;
		for (int t = 1; t <= 110 && t * velBala < dist(x, y, getX(), getY()); t++) {
			h += i.taxaGiro;
			x += Math.sin(h) * v;
			y += Math.cos(h) * v;
			double cx = limitar(x, RAIO_ROBO, larguraArena - RAIO_ROBO);
			double cy = limitar(y, RAIO_ROBO, alturaArena - RAIO_ROBO);
			if (cx != x || cy != y) { x = cx; y = cy; v = 0; }   // ele bateria na parede
		}
		return new Point2D.Double(x, y);
	}

	// ===========================================================================
	// radar
	// ===========================================================================
	private void girarRadar() {
		// 1x1 com dado fresco: trava no inimigo. Caso contrario varre tudo —
		// em melee saber onde estao todos vale mais que travar em um.
		if (getOthers() == 1 && alvo != null && getTime() - alvo.tempo < 3) {
			double ang = Math.atan2(alvo.x - getX(), alvo.y - getY());
			double giro = Utils.normalRelativeAngle(ang - getRadarHeadingRadians());
			setTurnRadarRightRadians(giro + (giro < 0 ? -0.35 : 0.35));
		} else {
			setTurnRadarRightRadians(Double.POSITIVE_INFINITY);
		}
	}

	// ===========================================================================
	// ondas
	// ===========================================================================
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
		double d = Math.max(Math.hypot(dx, dy), RAIO_ROBO);
		o.larguraFator = Math.max(Math.atan(RAIO_ROBO / d) / o.maxEscape, 0.06);

		// nossa velocidade lateral vista por ele define o que e "pra frente" (fator +1)
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

	/** Alvo = o mais proximo. Perto a bala erra menos e cada acerto devolve energia. */
	private Inimigo escolherAlvo() {
		Inimigo melhor = null;
		double menor = Double.MAX_VALUE;
		for (Inimigo i : inimigos.values()) {
			if (!i.vivo || !i.visto) continue;
			double d = dist(i.x, i.y, getX(), getY());
			if (getTime() - i.tempo > 12) d += 400;    // dado velho vale menos
			if (d < menor) { menor = d; melhor = i; }
		}
		return melhor;
	}

	// ===========================================================================
	// pintar
	// ===========================================================================
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

	// ===========================================================================
	// utilidades
	// ===========================================================================
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

	// ===========================================================================
	// classes
	// ===========================================================================
	/** Ultimo estado conhecido de um inimigo + o que aprendemos da mira dele. */
	private static class Inimigo {
		final double[] perigoAprendido = new double[BINS];
		double x, y, energia = 100, heading, velocidade, taxaGiro;
		double tiros;                     // quantos tiros dele ja nos acertaram
		long tempo;
		boolean vivo = true, visto = false;

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

		/** Levou um tiro nesse fator: marca a regiao como perigosa daqui pra frente. */
		void aprender(double fator) {
			int idx = binDe(fator);
			for (int i = 0; i < BINS; i++) perigoAprendido[i] += 1.0 / (1 + sq(i - idx));
			tiros++;
		}

		/**
		 * Perigo aprendido normalizado (0..2). A normalizacao e essencial: sem
		 * ela o acumulado cresceria round apos round ate ofuscar o peso da parede.
		 */
		double aprendido(double fator) {
			return tiros == 0 ? 0 : 2 * perigoAprendido[binDe(fator)] / tiros;
		}

		static int binDe(double fator) {
			return (int) limitar(Math.round((fator + 1) / 2 * (BINS - 1)), 0, BINS - 1);
		}
	}

	/** Uma bala inimiga que sabemos que existe, mas cujo angulo desconhecemos. */
	private static class Onda {
		Inimigo dono;
		double origemX, origemY, velocidade, poder;
		double anguloParaNos;   // angulo origem -> nos, no instante do disparo
		double maxEscape;       // maior angulo que da pra escapar (meio leque)
		double larguraFator;    // nossa largura convertida pra unidade de fator
		double fatorLinear;     // onde a mira linear dele cairia
		int sentido;            // nosso sentido lateral no disparo (+1 = "pra frente")
		long tempoDisparo;

		double raio(long t) { return (t - tempoDisparo) * velocidade; }

		double dano() { return 4 * poder + (poder > 1 ? 2 * (poder - 1) : 0); }

		/**
		 * Converte um ponto em "fator": -1 = fugindo pra tras ao maximo, 0 = onde
		 * estavamos quando ele atirou, +1 = fugindo pra frente ao maximo.
		 */
		double fatorDe(double x, double y) {
			double desvio = Utils.normalRelativeAngle(
					Math.atan2(x - origemX, y - origemY) - anguloParaNos);
			return limitar(desvio / maxEscape * sentido, -1, 1);
		}

		/**
		 * Probabilidade relativa de sermos interceptados nesse fator: duas
		 * suspeitas fixas (mira direta no fator 0, mira linear no fatorLinear)
		 * mais tudo que esse inimigo ja acertou na gente.
		 */
		double perigo(double fator) {
			return 1.4 / (1 + sq(fator / larguraFator))
			     + 1.0 / (1 + sq((fator - fatorLinear) / larguraFator))
			     + dono.aprendido(fator);
		}
	}
}
