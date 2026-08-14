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
 * ORDEM DE PRIORIDADE DO MOVIMENTO (de cima pra baixo, sem excecao)
 * ----------------------------------------------------------------
 * 1) NAO BATER NA PAREDE. Isso vem antes de tudo e e garantido por tres
 *    camadas independentes: o governador de velocidade (nunca andamos mais
 *    rapido do que da pra frear antes da borda), a suavizacao de rumo
 *    (nenhum rumo escolhido aponta pra fora da area segura) e o peso de
 *    parede na simulacao (que e maior que qualquer outro criterio junto).
 * 2) DESVIAR DAS BALAS. Como o Robocode nao deixa ver a bala inimiga, a gente
 *    ve a ENERGIA dele: queda entre 0.1 e 3.0 num tick = tiro. Nesse instante
 *    nasce uma ONDA (circulo que cresce na velocidade da bala). Nao sabemos o
 *    angulo, mas sabemos o circulo — e simulamos varios planos de fuga pra ver
 *    qual deles cruza as ondas nos pontos menos perigosos.
 * 3) FICAR LONGE DOS INIMIGOS. O robo NAO persegue ninguem: o desvio angular
 *    da rota e travado de forma que ele so consiga orbitar ou se afastar do
 *    foco, nunca fechar distancia (a unica excecao e quando esta longe demais
 *    pra acertar qualquer coisa, e ai a reaproximacao e minima).
 *
 * TIRO
 * ----
 * Sempre no inimigo MAIS PROXIMO — quanto menor a distancia, maior a taxa de
 * acerto, e cada acerto devolve 3*poder de energia pra gente. A mira e por
 * PREVISAO: simulamos a rota do inimigo (mantendo giro e velocidade atuais,
 * inclusive com o freio da parede) ate o tick em que a bala chegaria, e
 * atiramos onde ele VAI estar, nao onde ele esta.
 *
 * RAJADA
 * ------
 * Se por acaso um inimigo encostar na gente, o robo passa a descarregar poder
 * 3 toda vez que o canhao esfria, gastando energia de proposito, porque de
 * perto o acerto e praticamente garantido (e cada acerto devolve 9 de energia).
 * Isso e uma REACAO: o movimento nunca procura essa situacao, ela so acontece
 * quando o inimigo decide vir pra cima.
 */
public class Napolitano extends AdvancedRobot {

	// ---------------------------------------------------------------- fisica
	private static final double VEL_MAX    = 8.0;
	private static final double RAIO_ROBO  = 18.0;   // meia largura da caixa 36x36
	private static final int    BINS       = 31;     // resolucao do aprendizado de mira
	private static final int    MAX_PASSOS = 90;     // horizonte da simulacao (ticks)
	private static final int    JANELA     = 34;     // ticks avaliados p/ parede e distancia

	// ------------------------------------------------- parede (prioridade 1)
	private static final double MARGEM_FREIO = RAIO_ROBO + 6;   // onde o freio precisa terminar
	private static final double MARGEM_STICK = RAIO_ROBO + 22;  // area valida pra apontar o rumo
	private static final double MARGEM_MACIA = 90;              // a partir daqui a parede incomoda
	private static final double PESO_PAREDE  = 700;
	private static final double PESO_BATIDA  = 20000;

	// ---------------------------------------------- distancia (prioridade 3)
	private static final double DIST_DESEJADA = 400;  // conforto: abaixo disso queremos abrir
	private static final double DIST_LONGE    = 600;  // acima disso a mira ja nao acerta nada
	private static final double PESO_DISTANCIA = 120;
	private static final double PESO_PARADO    = 12;  // ficar parado e sempre um pouco ruim

	// ------------------------------------------------------------------ tiro
	private static final double DIST_RAJADA = 150;    // "colado": libera poder maximo

	// ---------------------------------------------------------------- estado
	private final Map<String, Inimigo> inimigos = new HashMap<String, Inimigo>();
	private final List<Onda> ondas = new ArrayList<Onda>();
	private final List<Point2D.Double> rota = new ArrayList<Point2D.Double>();

	private double larguraArena, alturaArena;
	private double refX, refY;      // centro da orbita (origem da onda mais urgente)
	private Inimigo alvo;
	private boolean emRajada;       // so pra pintar na tela

	// nossa posicao no tick anterior: e de la que as ondas inimigas "miraram"
	private double meuXAnt, meuYAnt, meuHeadingAnt, minhaVelAnt;

	// os 21 planos de fuga, montados uma vez so
	private static final List<Plano> PLANOS = new ArrayList<Plano>();
	static {
		// offset negativo = abre distancia | positivo = fecha (quase sempre travado em 0)
		double[] offsets = { -0.70, -0.45, -0.20, 0.0, 0.25 };
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
		// o que NAO se apaga e o perigoAprendido — e ele que acumula rounds.
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

		// Queda de energia entre 0.1 e 3.0 num intervalo curto = tiro dele.
		// Dois filtros contra onda fantasma: dado velho (nao sabemos de onde
		// saiu a bala) e batida dele na parede (perde ate 3.0 e para na hora).
		double queda = i.energia - e.getEnergy();
		boolean recente = getTime() - i.tempo <= 6;
		boolean bateu = Math.abs(e.getVelocity()) < 0.5 && Math.abs(i.velocidade) > 2.5;
		if (i.visto && recente && !bateu && queda >= 0.09 && queda <= 3.01) {
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
		// encostou: e o cenario da rajada no seu extremo, a bala nao tem como
		// errar. Atira o mais forte que a energia permite e sai de cima.
		double poder = poderDeRajada();
		if (getGunHeat() == 0 && getEnergy() > poder + 0.15) setFire(poder);
		setBack(60);
	}

	public void onHitWall(HitWallEvent e) {
		// as tres camadas de protecao falharam. Zera a inercia e volta pro campo
		// antes de qualquer outra decisao (o proximo tick recalcula tudo).
		setMaxVelocity(VEL_MAX);
		setBack(40);
	}

	public void onRobotDeath(RobotDeathEvent e) {
		Inimigo i = inimigos.get(e.getName());
		if (i != null) i.vivo = false;
	}

	// ============================================================== movimento
	private void mover() {
		// foco da orbita: a onda mais urgente; sem ondas, o inimigo mais proximo
		// (pra orbitar, nunca pra perseguir); sem ninguem, o centro
		Onda urgente = ondaMaisUrgente();
		if (urgente != null)   { refX = urgente.origemX;  refY = urgente.origemY; }
		else if (alvo != null) { refX = alvo.x;           refY = alvo.y;          }
		else                   { refX = larguraArena / 2; refY = alturaArena / 2; }

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
		setMaxVelocity(Math.min(melhor.velAlvo, tetoDeVelocidade(getX(), getY(),
				getHeadingRadians(), re)));
		setAhead(re * 100);
	}

	/**
	 * GOVERNADOR DE PAREDE — a garantia mais forte de que nao batemos.
	 * Mede quanto chao livre existe na direcao em que estamos de fato andando e
	 * devolve a maior velocidade da qual ainda da pra frear dentro desse espaco.
	 * Como o Robocode freia 2/tick, parar de velocidade v custa v*v/4 + v/2 de
	 * chao; invertendo isso: v = sqrt(1 + 4*livre) - 1.
	 */
	private double tetoDeVelocidade(double x, double y, double heading, double re) {
		double livre = distanciaAteParede(x, y, heading + (re > 0 ? 0 : Math.PI));
		return limitar(Math.sqrt(1 + 4 * livre) - 1, 0, VEL_MAX);
	}

	/** Quanto chao livre existe de (x,y) seguindo 'dir' ate a borda da area de freio. */
	private double distanciaAteParede(double x, double y, double dir) {
		double dx = Math.sin(dir), dy = Math.cos(dir);
		double d = Double.MAX_VALUE;
		if (dx >  1e-9) d = Math.min(d, (larguraArena - MARGEM_FREIO - x) / dx);
		if (dx < -1e-9) d = Math.min(d, (MARGEM_FREIO - x) / dx);
		if (dy >  1e-9) d = Math.min(d, (alturaArena - MARGEM_FREIO - y) / dy);
		if (dy < -1e-9) d = Math.min(d, (MARGEM_FREIO - y) / dy);
		return Math.max(d, 0);
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
		double nota = p.velAlvo == 0 ? PESO_PARADO : 0;
		double somaParede = 0, somaDistancia = 0;

		for (int passo = 1; passo <= MAX_PASSOS && (restantes > 0 || passo <= JANELA); passo++) {
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
			// o governador vale aqui tambem, senao a simulacao preveria uma rota
			// mais agressiva do que a que o robo vai realmente executar
			v = proximaVelocidade(v, re * Math.min(p.velAlvo, tetoDeVelocidade(x, y, h, re)));
			x += Math.sin(h) * v;
			y += Math.cos(h) * v;

			// bateu na parede: o robo para e leva multa altissima
			double cx = limitar(x, RAIO_ROBO, larguraArena - RAIO_ROBO);
			double cy = limitar(y, RAIO_ROBO, alturaArena - RAIO_ROBO);
			if (cx != x || cy != y) { x = cx; y = cy; v = 0; nota += PESO_BATIDA / passo; }

			if (traco != null) traco.add(new Point2D.Double(x, y));

			// parede e distancia sao avaliadas numa janela fixa, igual pra todos
			// os planos — assim a comparacao entre eles e justa
			if (passo <= JANELA) {
				somaParede += perigoParede(x, y);
				for (Inimigo i : inimigos.values())
					if (i.vivo) somaDistancia += perigoDistancia(dist(i.x, i.y, x, y));
			}

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
		return nota + PESO_PAREDE * somaParede / JANELA
		            + PESO_DISTANCIA * somaDistancia / JANELA;
	}

	/**
	 * Perigo de estar em (x,y) por causa da parede. Vale 0 enquanto estamos a
	 * mais de MARGEM_MACIA de qualquer borda — ou seja, no campo aberto a parede
	 * nao interfere na escolha — e cresce ao cubo conforme encosta, ficando maior
	 * que qualquer outro criterio bem antes de virar colisao.
	 */
	private double perigoParede(double x, double y) {
		double d = Math.min(Math.min(x, larguraArena - x), Math.min(y, alturaArena - y));
		if (d >= MARGEM_MACIA) return 0;
		double t = (MARGEM_MACIA - d) / (MARGEM_MACIA - RAIO_ROBO);
		return t * t * t;
	}

	/**
	 * Perigo de estar a essa distancia de um inimigo. Perto e ruim de verdade
	 * (cresce ao quadrado), longe demais e so um pouco ruim — o suficiente pra
	 * ele nao se exilar num canto sem conseguir acertar nada.
	 */
	private double perigoDistancia(double d) {
		if (d < DIST_DESEJADA) return sq((DIST_DESEJADA - d) / DIST_DESEJADA);
		if (d > DIST_LONGE)    return 0.25 * Math.min(1, (d - DIST_LONGE) / 400);
		return 0;
	}

	/**
	 * Rumo desejado: orbita ao redor do foco, com desvio angular, trava de
	 * aproximacao e desvio de parede.
	 *
	 * A trava e o que impede a perseguicao: o desvio positivo (que fecharia
	 * distancia) so e liberado quando estamos alem de DIST_LONGE. Dentro da
	 * distancia de conforto o vies empurra ativamente pra fora.
	 */
	private double rumoDoPlano(Plano p, double x, double y) {
		double paraFora = Math.atan2(x - refX, y - refY);
		double d = dist(x, y, refX, refY);
		double teto = d > DIST_LONGE ? 0.35 : 0.0;
		double off = limitar(p.offset + viesDeDistancia(d), -1.0, teto);
		return suavizarParede(x, y, paraFora + p.sentido * (Math.PI / 2 + off), p.sentido);
	}

	/** Empurrao angular: negativo abre distancia, positivo fecha. */
	private double viesDeDistancia(double d) {
		if (d < DIST_DESEJADA) return -0.9 * (DIST_DESEJADA - d) / DIST_DESEJADA;
		if (d > DIST_LONGE)    return  0.35 * Math.min(1, (d - DIST_LONGE) / 300);
		return 0;
	}

	/**
	 * Gira o rumo desejado ate que o ponto ~140 a frente caia dentro da area
	 * segura. E isso que faz o robo escolher o outro lado quando a parede corta
	 * o desvio "natural", em vez de insistir e raspar na borda.
	 */
	private double suavizarParede(double x, double y, double rumo, int sentido) {
		for (int i = 0; i < 45; i++) {
			double px = x + Math.sin(rumo) * 140;
			double py = y + Math.cos(rumo) * 140;
			if (px > MARGEM_STICK && px < larguraArena - MARGEM_STICK
			 && py > MARGEM_STICK && py < alturaArena - MARGEM_STICK) break;
			rumo += sentido * 0.14;
		}
		return rumo;
	}

	// =================================================================== tiro
	private void mirar(Inimigo i) {
		double d = dist(i.x, i.y, getX(), getY());
		emRajada = d <= DIST_RAJADA;

		// RAJADA: colado nao existe motivo pra economizar. Poder 3 devolve 9 de
		// energia por acerto, entao gastar aqui e investimento, nao desperdicio.
		double poder = emRajada ? poderDeRajada() : escolherPoder(i, d);
		double velBala = 20 - 3 * poder;

		Point2D.Double p = preverPosicao(i, velBala);
		double ang = Math.atan2(p.x - getX(), p.y - getY());
		setTurnGunRightRadians(Utils.normalRelativeAngle(ang - getGunHeadingRadians()));

		// so atira com o canhao dentro da largura angular do inimigo. De perto
		// essa largura ja e enorme, entao a rajada sai praticamente todo tick
		// em que o canhao esta frio.
		double tolerancia = Math.atan(RAIO_ROBO / Math.max(d, RAIO_ROBO));
		if (Math.abs(getGunTurnRemainingRadians()) < tolerancia
		 && getGunHeat() == 0 && getEnergy() > poder + 0.15) {
			setFire(poder);
		}
	}

	/**
	 * Poder da rajada de curta distancia: o maximo que da, limitado so pela
	 * sobrevivencia e pelo exagero (nao gastamos 3 num inimigo que morre com 1).
	 *
	 * A conta que autoriza esse gasto: poder 3 custa 3 e devolve 9 por acerto,
	 * entao a rajada se paga com qualquer taxa de acerto acima de 1/3 — e de
	 * perto ela e muito maior que isso. O unico cuidado e nao esvaziar o tanque,
	 * porque energia 0 desliga o robo: abaixo de 24 o poder passa a ser
	 * proporcional a energia restante, entao ele nunca se mata atirando.
	 */
	private double poderDeRajada() {
		double poder = 3;
		if (getEnergy() < 24) poder = getEnergy() / 8;
		if (alvo != null) poder = Math.min(poder, alvo.energia / 4 + 0.1);
		return limitar(poder, 0.1, 3);
	}

	/**
	 * Calor do canhao: depois do tiro ele fica 1 + poder/5 quente e esfria 0.1
	 * por tick. Poder 3 = 16 ticks parado; poder 1 = 12 ticks. Logo tiro forte
	 * so compensa quando a chance de acertar e alta (perto). De longe vale mais
	 * tiro fraco: sai mais vezes e a bala e mais rapida, o que dificulta a
	 * esquiva do outro.
	 */
	private double escolherPoder(Inimigo i, double d) {
		double poder = 600 / d;                      // 200 -> 3 | 300 -> 2 | 600 -> 1
		if (getEnergy() < 30) poder = Math.min(poder, getEnergy() / 8);
		poder = Math.min(poder, i.energia / 4 + 0.1); // nao desperdica no golpe final
		if (getOthers() > 2) poder = Math.min(poder, 2);  // melee: energia e vida
		return limitar(poder, 0.1, 3);
	}

	/**
	 * PREVISAO DE TIRO. Roda a rota do inimigo tick a tick — mantendo o giro e a
	 * velocidade que ele tem agora, e travando na parede como o jogo faria — ate
	 * o tick em que a bala teria percorrido a distancia. O retorno e o ponto onde
	 * ele deve estar quando a bala chegar. Quando o giro dele e ~0 isso vira
	 * naturalmente uma mira linear.
	 */
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

	/** Sempre o mais proximo: e nele que a taxa de acerto — e o retorno de energia — e maior. */
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
		// area segura: enquanto o traco verde estiver dentro dela, a parede nao
		// esta influenciando a decisao
		g.setColor(new Color(255, 255, 255, 60));
		g.drawRect((int) MARGEM_MACIA, (int) MARGEM_MACIA,
		           (int) (larguraArena - 2 * MARGEM_MACIA), (int) (alturaArena - 2 * MARGEM_MACIA));

		g.setColor(new Color(255, 80, 80, 140));
		for (int k = 0; k < ondas.size(); k++) {
			Onda o = ondas.get(k);
			int r = (int) o.raio(getTime());
			g.drawOval((int) o.origemX - r, (int) o.origemY - r, r * 2, r * 2);
		}

		g.setColor(emRajada ? new Color(255, 220, 60, 220) : new Color(120, 255, 120, 200));
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
		final double offset;    // desvio do perpendicular (negativo = afasta)
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

		void reiniciarRound() { vivo = true; visto = false; energia = 100; taxaGiro = 0; }

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
